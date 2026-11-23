package ru.miacomsoft.live360viewer.stream;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CapturerObserver;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class WebRtcClient implements SignalingServer.Listener {

    private static final String TAG = "WebRtcClient";

    // ★ VOICE_COMMUNICATION (7) — на RK3528 c USB-микрофоном камеры даёт
    //   реальный поток PCM (проверено на Huawei CV60 + H96 Max).
    //   CAMCORDER/MIC могут «включать индикатор», но возвращать тишину.
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION;

    private final Context context;
    private final SignalingServer server;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private SurfaceTextureCapturer videoCapturer;
    private VideoTrack videoTrack;
    private EglBase eglBase;
    private org.java_websocket.WebSocket clientConnection;

    private JavaAudioDeviceModule audioDeviceModule;
    private AudioSource audioSource;
    private AudioTrack audioTrack;

    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor();

    public WebRtcClient(Context context, int signalingPort) {
        this.context = context.getApplicationContext();
        this.server = new SignalingServer(signalingPort, this);
    }

    public void start() {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                            .builder(context)
                            .createInitializationOptions());

            eglBase = EglBase.create();

            DefaultVideoEncoderFactory encoderFactory =
                    new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);

            // ★ Диагностика входных аудио-устройств
            logAudioInputDevices();
            // ★ Активируем USB-микрофон как communication device (Android 12+)
            preferUsbMicAsCommunicationDevice();

            Log.i(TAG, "createAudioDeviceModule: audioSource=" + AUDIO_SOURCE);
            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setAudioSource(AUDIO_SOURCE)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .setUseStereoInput(false)
                    .setUseStereoOutput(false)
                    .createAudioDeviceModule();

            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory();

            server.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebRTC", e);
        }
    }

    // ============ AUDIO DIAGNOSTICS ============

    private void logAudioInputDevices() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            Log.i(TAG, "Audio input devices: " + inputs.length);
            for (AudioDeviceInfo d : inputs) {
                Log.i(TAG, "  input id=" + d.getId()
                        + " type=" + d.getType()
                        + " name=" + d.getProductName()
                        + " srcs=" + java.util.Arrays.toString(d.getSampleRates())
                        + " ch=" + java.util.Arrays.toString(d.getChannelCounts()));
            }
        } catch (Exception e) {
            Log.w(TAG, "logAudioInputDevices: " + e.getMessage());
        }
    }

    /**
     * Android 12+: явно назначаем USB-микрофон устройством связи.
     * Это влияет на источник VOICE_COMMUNICATION — WebRTC через
     * AudioRecord начнёт читать PCM с USB-микрофона камеры, а не
     * с несуществующего «встроенного» микрофона.
     */
    private void preferUsbMicAsCommunicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "preferUsbMicAsCommunicationDevice: requires Android 12+, skip");
            return;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            AudioDeviceInfo usbMic = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                        || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    usbMic = d;
                    break;
                }
            }
            if (usbMic == null) {
                Log.w(TAG, "preferUsbMicAsCommunicationDevice: USB mic not found");
                return;
            }
            boolean ok = am.setCommunicationDevice(usbMic);
            Log.i(TAG, "setCommunicationDevice(" + usbMic.getProductName()
                    + ") -> " + ok);
        } catch (Exception e) {
            Log.w(TAG, "preferUsbMicAsCommunicationDevice: " + e.getMessage());
        }
    }

    // ============ VIDEO ============

    public Surface getCaptureSurface() {
        if (videoCapturer == null) {
            SurfaceTextureHelper helper =
                    SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoCapturer = new SurfaceTextureCapturer();
            VideoSource videoSource = factory.createVideoSource(false);

            videoCapturer.initialize(helper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 640, 30);

            videoTrack = factory.createVideoTrack("VideoTrack", videoSource);
            Log.i(TAG, "Video track created: " + videoTrack.id());
            return videoCapturer.getSurface();
        }
        return videoCapturer.getSurface();
    }

    @Override
    public void onClientConnected(org.java_websocket.WebSocket conn) {
        Log.d(TAG, "WebRTC Client connected");
        this.clientConnection = conn;
        createPeerConnection();
        createOffer();
    }

    @Override
    public void onMessage(org.java_websocket.WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");
            if ("answer".equals(type)) {
                String sdp = json.getString("sdp");
                Log.d(TAG, "Received Answer from Browser");
                if (peerConnection != null) {
                    peerConnection.setRemoteDescription(new SimpleSdpObserver(),
                            new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                }
            } else if ("candidate".equals(type)) {
                JSONObject cand = json.getJSONObject("candidate");
                String sdpMid = cand.optString("sdpMid", null);
                int sdpMLineIndex = cand.optInt("sdpMLineIndex", 0);
                String candidateStr = cand.optString("candidate", null);

                if (sdpMid != null && candidateStr != null && peerConnection != null) {
                    Log.d(TAG, "Received ICE Candidate from Browser");
                    peerConnection.addIceCandidate(
                            new IceCandidate(sdpMid, sdpMLineIndex, candidateStr));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Signaling error: " + e.getMessage());
        }
    }

    @Override
    public void onClientDisconnected(org.java_websocket.WebSocket conn) {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        clientConnection = null;
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer());

        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                try {
                    JSONObject cand = new JSONObject();
                    cand.put("sdpMid", iceCandidate.sdpMid);
                    cand.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    cand.put("candidate", iceCandidate.sdp);
                    JSONObject msg = new JSONObject();
                    msg.put("type", "candidate");
                    msg.put("candidate", cand);
                    wsExecutor.execute(() -> {
                        if (clientConnection != null) {
                            clientConnection.send(msg.toString());
                        }
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE State: " + s);
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel dc) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] ms) {}
        });

        // ---- AUDIO ----
        try {
            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("googEchoCancellation", "false"));
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("googNoiseSuppression", "false"));
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("googHighpassFilter", "false"));

            audioSource = factory.createAudioSource(audioConstraints);
            audioTrack = factory.createAudioTrack("audio_track", audioSource);
            audioTrack.setEnabled(true);

            RtpSender audioSender = peerConnection.addTrack(
                    audioTrack, Collections.singletonList("ARDAMS"));
            Log.i(TAG, "Audio track added: " + (audioSender != null)
                    + " (track=" + audioTrack.id() + ", enabled=" + audioTrack.enabled() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add audio track", e);
        }

        // ---- VIDEO ----
        if (videoTrack != null) {
            RtpSender videoSender = peerConnection.addTrack(
                    videoTrack, Collections.singletonList("ARDAMS"));
            Log.i(TAG, "Video track added: " + (videoSender != null)
                    + " (track=" + videoTrack.id() + ")");
        }
    }

    private void createOffer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "offer");
                    msg.put("sdp", sessionDescription.description);
                    wsExecutor.execute(() -> {
                        if (clientConnection != null) {
                            Log.d(TAG, "Sending Offer (audio=" + (audioTrack != null)
                                    + ", video=" + (videoTrack != null) + ")");
                            clientConnection.send(msg.toString());
                        }
                    });
                } catch (Exception ignored) {}
            }
        }, constraints);
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e(TAG, "SDP Failure: " + s); }
        @Override public void onSetFailure(String s)  { Log.e(TAG, "SDP Failure: " + s); }
    }

    private static class SurfaceTextureCapturer implements VideoCapturer, VideoSink {
        private static final String T = "SurfaceTextureCapturer";
        private SurfaceTextureHelper helper;
        private CapturerObserver observer;
        private android.graphics.SurfaceTexture surfaceTexture;
        private Surface surface;
        private final AtomicLong frameCounter = new AtomicLong(0);

        @Override
        public void initialize(SurfaceTextureHelper surfaceTextureHelper,
                               Context applicationContext,
                               CapturerObserver capturerObserver) {
            Log.i(T, "initialize");
            this.helper = surfaceTextureHelper;
            this.observer = capturerObserver;
            this.surfaceTexture = helper.getSurfaceTexture();

            this.surfaceTexture.setDefaultBufferSize(1280, 640);
            this.helper.setTextureSize(1280, 640);

            this.surface = new Surface(surfaceTexture);
            helper.startListening(this);
        }

        @Override
        public void startCapture(int width, int height, int framerate) {
            Log.i(T, "startCapture " + width + "x" + height + "@" + framerate);
            if (helper != null) helper.setTextureSize(width, height);
            if (observer != null) observer.onCapturerStarted(true);
        }

        @Override
        public void stopCapture() {
            Log.i(T, "stopCapture");
            if (observer != null) observer.onCapturerStopped();
        }

        @Override
        public void changeCaptureFormat(int width, int height, int framerate) {
            Log.i(T, "changeCaptureFormat " + width + "x" + height + "@" + framerate);
            if (helper != null) helper.setTextureSize(width, height);
        }

        @Override
        public void dispose() {
            Log.i(T, "dispose");
            if (surface != null) surface.release();
            if (surfaceTexture != null) surfaceTexture.release();
            if (helper != null) helper.dispose();
        }

        @Override public boolean isScreencast() { return false; }

        @Override
        public void onFrame(VideoFrame frame) {
            if (observer != null) {
                long n = frameCounter.incrementAndGet();
                if ((n & 0x3F) == 1) {
                    Log.d(T, "onFrame #" + n + " " + frame.getBuffer().getWidth()
                            + "x" + frame.getBuffer().getHeight());
                }
                observer.onFrameCaptured(frame);
            }
        }

        public Surface getSurface() { return surface; }
    }
}