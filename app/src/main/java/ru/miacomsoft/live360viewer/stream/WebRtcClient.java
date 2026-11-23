package ru.miacomsoft.live360viewer.stream;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;

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

public class WebRtcClient implements SignalingServer.Listener {

    private static final String TAG = "WebRtcClient";

    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;

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

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission NOT granted");
            } else {
                Log.i(TAG, "RECORD_AUDIO permission granted");
            }

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

    public void stop() {
        Log.i(TAG, "Stopping WebRtcClient...");

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
        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }
        if (videoCapturer != null) {
            videoCapturer.stopCapture();
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (audioDeviceModule != null) {
            audioDeviceModule.release();
            audioDeviceModule = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        if (server != null) {
            try {
                server.stop();
                Log.i(TAG, "Signaling server stopped");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping server", e);
            }
        }
        wsExecutor.shutdown();
    }

    public Surface getCaptureSurface() {
        if (videoCapturer == null) {
            SurfaceTextureHelper helper =
                    SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoCapturer = new SurfaceTextureCapturer();
            VideoSource videoSource = factory.createVideoSource(false);

            videoCapturer.initialize(helper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 640, 30);

            videoTrack = factory.createVideoTrack("VideoTrack", videoSource);
            return videoCapturer.getSurface();
        }
        return videoCapturer.getSurface();
    }

    public void updateVideoResolution(int width, int height) {
        if (videoCapturer != null) {
            Log.i(TAG, "updateVideoResolution: " + width + "x" + height);
            videoCapturer.changeCaptureFormat(width, height, 30);
        }
    }

    @Override
    public void onClientConnected(org.java_websocket.WebSocket conn) {
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
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
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
            @Override public void onIceCandidate(IceCandidate iceCandidate) {
                try {
                    JSONObject cand = new JSONObject();
                    cand.put("sdpMid", iceCandidate.sdpMid);
                    cand.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    cand.put("candidate", iceCandidate.sdp);
                    JSONObject msg = new JSONObject();
                    msg.put("type", "candidate");
                    msg.put("candidate", cand);
                    wsExecutor.execute(() -> {
                        if (clientConnection != null) clientConnection.send(msg.toString());
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel dc) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] ms) {}
        });

        try {
            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "false"));

            audioSource = factory.createAudioSource(audioConstraints);
            audioTrack = factory.createAudioTrack("audio_track", audioSource);
            audioTrack.setEnabled(true);

            RtpSender audioSender = peerConnection.addTrack(audioTrack, Collections.singletonList("ARDAMS"));
            Log.i(TAG, "Audio track added: " + (audioSender != null));
        } catch (Exception e) {
            Log.e(TAG, "Failed to add audio track", e);
        }

        if (videoTrack != null) {
            RtpSender videoSender = peerConnection.addTrack(videoTrack, Collections.singletonList("ARDAMS"));
            Log.i(TAG, "Video track added: " + (videoSender != null));
        }
    }

    private void createOffer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "offer");
                    msg.put("sdp", sessionDescription.description);
                    wsExecutor.execute(() -> {
                        if (clientConnection != null) clientConnection.send(msg.toString());
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
        private SurfaceTextureHelper helper;
        private CapturerObserver observer;
        private android.graphics.SurfaceTexture surfaceTexture;
        private Surface surface;

        @Override
        public void initialize(SurfaceTextureHelper helper, Context appContext, CapturerObserver observer) {
            this.helper = helper;
            this.observer = observer;
            this.surfaceTexture = helper.getSurfaceTexture();
            this.surfaceTexture.setDefaultBufferSize(1280, 640);
            this.helper.setTextureSize(1280, 640);
            this.surface = new Surface(surfaceTexture);
            helper.startListening(this);
        }

        @Override
        public void startCapture(int w, int h, int fps) {
            if (helper != null) helper.setTextureSize(w, h);
            if (observer != null) observer.onCapturerStarted(true);
        }

        @Override public void stopCapture() { if (observer != null) observer.onCapturerStopped(); }

        @Override
        public void changeCaptureFormat(int w, int h, int fps) {
            if (helper != null) helper.setTextureSize(w, h);
            if (surfaceTexture != null) surfaceTexture.setDefaultBufferSize(w, h);
        }

        @Override public void dispose() {
            if (surface != null) surface.release();
            if (surfaceTexture != null) surfaceTexture.release();
            if (helper != null) helper.dispose();
        }
        @Override public boolean isScreencast() { return false; }
        @Override public void onFrame(VideoFrame frame) {
            if (observer != null) observer.onFrameCaptured(frame);
        }
        public Surface getSurface() { return surface; }
    }
}