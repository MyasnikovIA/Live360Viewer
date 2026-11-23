package ru.miacomsoft.live360viewer.stream;

import android.content.Context;
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
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRtcClient implements SignalingServer.Listener {

    private static final String TAG = "WebRtcClient";

    private final Context context;
    private final SignalingServer server;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private SurfaceTextureCapturer videoCapturer;
    private VideoTrack videoTrack;
    private EglBase eglBase;
    private org.java_websocket.WebSocket clientConnection;

    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor();

    public WebRtcClient(Context context, int signalingPort) {
        this.context = context.getApplicationContext();
        this.server = new SignalingServer(signalingPort, this);
    }

    public void start() {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());

            eglBase = EglBase.create();

            // Отключаем аудио, так как микрофон на приставке RK3528 вызывает ошибку AudioRecord
            DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);

            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .createPeerConnectionFactory();

            server.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebRTC", e);
        }
    }

    public Surface getCaptureSurface() {
        if (videoCapturer == null) {
            SurfaceTextureHelper helper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoCapturer = new SurfaceTextureCapturer();
            VideoSource videoSource = factory.createVideoSource(false);

            videoCapturer.initialize(helper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 640, 30);

            videoTrack = factory.createVideoTrack("VideoTrack", videoSource);
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
                    peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                }
            } else if ("candidate".equals(type)) {
                JSONObject cand = json.getJSONObject("candidate");
                String sdpMid = cand.optString("sdpMid", null);
                int sdpMLineIndex = cand.optInt("sdpMLineIndex", 0);
                String candidateStr = cand.optString("candidate", null);

                if (sdpMid != null && candidateStr != null && peerConnection != null) {
                    Log.d(TAG, "Received ICE Candidate from Browser");
                    peerConnection.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidateStr));
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
        clientConnection = null;
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
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
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE State: " + iceConnectionState);
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
        });

        // Отправляем ТОЛЬКО ВИДЕО (без аудио)
        if (videoTrack != null) {
            peerConnection.addTrack(videoTrack, Collections.singletonList("ARDAMS"));
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
                        if (clientConnection != null) {
                            Log.d(TAG, "Sending Offer to Browser");
                            clientConnection.send(msg.toString());
                        }
                    });
                } catch (Exception ignored) {}
            }
        }, constraints);
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e(TAG, "SDP Failure: " + s); }
        @Override public void onSetFailure(String s) { Log.e(TAG, "SDP Failure: " + s); }
    }

    private static class SurfaceTextureCapturer implements VideoCapturer, VideoSink {
        private SurfaceTextureHelper helper;
        private CapturerObserver observer;
        private android.graphics.SurfaceTexture surfaceTexture;
        private Surface surface;

        @Override
        public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver capturerObserver) {
            this.helper = surfaceTextureHelper;
            this.observer = capturerObserver;
            this.surfaceTexture = helper.getSurfaceTexture();

            // ★ Устанавливаем размер для декодера и для WebRTC
            this.surfaceTexture.setDefaultBufferSize(1280, 640);
            this.helper.setTextureSize(1280, 640);

            this.surface = new Surface(surfaceTexture);
            helper.startListening(this);
        }

        @Override
        public void startCapture(int width, int height, int framerate) {
            if (helper != null) {
                helper.setTextureSize(width, height);
            }
        }

        @Override
        public void stopCapture() {}
        @Override
        public void changeCaptureFormat(int width, int height, int framerate) {
            if (helper != null) helper.setTextureSize(width, height);
        }

        @Override
        public void dispose() {
            if (surface != null) surface.release();
            if (surfaceTexture != null) surfaceTexture.release();
            if (helper != null) helper.dispose();
        }

        @Override
        public boolean isScreencast() { return false; }

        @Override
        public void onFrame(VideoFrame frame) {
            if (observer != null) {
                observer.onFrameCaptured(frame);
            }
        }

        public Surface getSurface() { return surface; }
    }
}