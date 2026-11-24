package ru.miacomsoft.live360viewer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import ru.miacomsoft.live360viewer.decoder.H264Decoder;
import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.stream.WebRtcClient;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

public class StreamerActivity extends Activity {

    private static final String TAG = "StreamerActivity";
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int SIGNALING_PORT = 9090;

    private TextView tvStatus;
    private Button btnChangeRes;
    private Button btnStop;

    private WebRtcClient webRtcClient;
    private UsbCameraManager usbManager;
    private LiveViewSession liveViewSession;
    private H264Decoder h264Decoder;

    private boolean pendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer);

        tvStatus = findViewById(R.id.tvStatus);
        btnChangeRes = findViewById(R.id.btnChangeRes);
        btnStop = findViewById(R.id.btnStop);

        btnChangeRes.setOnClickListener(v -> showResolutionDialog());
        btnStop.setOnClickListener(v -> {
            finish(); // Закрываем Activity. Ресурсы освободятся в onDestroy()
        });

        if (!hasRecordAudioPermission()) {
            tvStatus.setText("Требуется разрешение на микрофон…");
            requestRecordAudioPermission();
        } else {
            startStreaming();
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordAudioPermission() {
        pendingStart = true;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQ_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на микрофон выдано", Toast.LENGTH_SHORT).show();
            } else {
                tvStatus.setText("Разрешение на микрофон отклонено. Звук не будет работать.");
            }
            if (pendingStart) {
                pendingStart = false;
                startStreaming();
            }
        }
    }

    private void startStreaming() {
        webRtcClient = new WebRtcClient(this, SIGNALING_PORT);
        webRtcClient.start();

        Surface surface = webRtcClient.getCaptureSurface();
        h264Decoder = new H264Decoder(surface, 1280, 640);
        try {
            h264Decoder.start();
            Log.i(TAG, "H264Decoder started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start H264Decoder", e);
            tvStatus.setText("Ошибка декодера: " + e.getMessage());
        }

        tvStatus.setText("WebRTC сервер запущен на порту " + SIGNALING_PORT);

        usbManager = new UsbCameraManager(this);
        usbManager.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(android.hardware.usb.UsbDeviceConnection conn,
                                    android.hardware.usb.UsbInterface intf,
                                    android.hardware.usb.UsbEndpoint epIn,
                                    android.hardware.usb.UsbEndpoint epOut) {
                liveViewSession = new LiveViewSession(conn, epIn, epOut, 0x03, 0x0A,
                        new LiveViewSession.FrameListener() {
                            @Override
                            public void onVideoFrame(byte[] h264, long ptsUs) {
                                if (h264Decoder != null) {
                                    h264Decoder.feed(h264, ptsUs);
                                }
                            }
                            @Override
                            public void onError(String msg) {
                                runOnUiThread(() -> tvStatus.setText("Ошибка камеры: " + msg));
                            }
                        });
                liveViewSession.start();
            }

            @Override public void onDetached() {}
            @Override public void onPermissionDenied() {
                runOnUiThread(() -> Toast.makeText(StreamerActivity.this, "Нет прав USB", Toast.LENGTH_SHORT).show());
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> tvStatus.setText("Ошибка USB: " + msg));
            }
        });
    }

    private void showResolutionDialog() {
        if (liveViewSession == null) {
            Toast.makeText(this, "Камера еще не запущена", Toast.LENGTH_SHORT).show();
            return;
        }

        int[] videoCodes = PhotoSettings.allVideoCodes();
        String[] labels = new String[videoCodes.length];
        for (int i = 0; i < videoCodes.length; i++) {
            labels[i] = PhotoSettings.labelVideo(videoCodes[i]);
        }

        new AlertDialog.Builder(this)
                .setTitle("Выберите разрешение")
                .setItems(labels, (dialog, which) -> changeVideoResolution(videoCodes[which]))
                .show();
    }

    private void changeVideoResolution(int videoResCode) {
        int width, height;
        switch (videoResCode) {
            case PhotoSettings.VIDEO_RES_640x320:  width = 640;  height = 320;  break;
            case PhotoSettings.VIDEO_RES_960x480:  width = 960;  height = 480;  break;
            case PhotoSettings.VIDEO_RES_1024x512: width = 1024; height = 512;  break;
            case PhotoSettings.VIDEO_RES_1280x640: width = 1280; height = 640;  break;
            case PhotoSettings.VIDEO_RES_1920x960: width = 1920; height = 960;  break;
            default: width = 1280; height = 640; break;
        }

        Log.i(TAG, "Changing resolution to " + width + "x" + height);

        if (liveViewSession != null) {
            new Thread(() -> liveViewSession.changeVideoRes(videoResCode)).start();
        }
        if (webRtcClient != null) {
            webRtcClient.updateVideoResolution(width, height);
        }

        tvStatus.setText("Разрешение изменено на " + width + "x" + height);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy: releasing all resources");
        if (liveViewSession != null) {
            liveViewSession.stop();
            liveViewSession = null;
        }
        if (h264Decoder != null) {
            h264Decoder.stop();
            h264Decoder = null;
        }
        if (usbManager != null) {
            usbManager.stop();
            usbManager = null;
        }
        if (webRtcClient != null) {
            webRtcClient.stop();
            webRtcClient = null;
        }
    }
}
