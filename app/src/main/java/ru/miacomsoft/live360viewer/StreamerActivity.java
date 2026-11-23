package ru.miacomsoft.live360viewer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;

import ru.miacomsoft.live360viewer.audio.AudioStreamSource;
import ru.miacomsoft.live360viewer.decoder.H264Decoder;
import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.stream.WebRtcClient;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

public class StreamerActivity extends AppCompatActivity {
    private static final String TAG = "StreamerActivity";
    private static final int REQ_RECORD_AUDIO = 2001;

    private TextView tvStatus, tvLog;
    private WebRtcClient webRtcClient;
    private H264Decoder decoder;
    private UsbCameraManager usb;
    private LiveViewSession session;
    private Surface webRtcSurface;

    private volatile boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        Button btnStop = findViewById(R.id.btnStop);

        btnStop.setOnClickListener(v -> finish());

        ensurePermissions();
        startWebRTC();
    }

    private void ensurePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    private void startWebRTC() {
        // Используем 8888 порт для WebSocket
        webRtcClient = new WebRtcClient(this, 9090);
        webRtcClient.start();

        // Получаем Surface от WebRTC для декодера
        webRtcSurface = webRtcClient.getCaptureSurface();

        tvStatus.setText("Ожидание WebRTC клиента на 9090");
        log("WebRTC Started. Open HTML page.");

        startCamera();
    }

    private void startCamera() {
        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice cam = findCamera(mgr);
        if (cam == null) { log("Camera not found"); return; }

        streaming = true;
        final int photoRes = PhotoSettings.getResolution(this);
        final int videoRes = PhotoSettings.getVideoResolution(this);

        decoder = new H264Decoder(webRtcSurface, 1280, 640);
        try {
            decoder.start();
        } catch (Exception e) {
            log("Decoder start failed: " + e.getMessage());
            return;
        }

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override public void onConnected(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
                session = new LiveViewSession(conn, in, out, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                if (decoder != null) decoder.feed(h264, ptsUs);
                            }
                            @Override public void onError(String msg) { Log.e(TAG, "LiveView: " + msg); }
                        });
                new Thread(() -> {
                    boolean ok = session.start();
                    runOnUiThread(() -> log(ok ? "Camera streaming to WebRTC" : "Camera failed"));
                }, "LV-start").start();
            }
            @Override public void onDetached() { streaming = false; }
            @Override public void onPermissionDenied() { streaming = false; }
            @Override public void onError(String msg) { streaming = false; }
        });
    }

    private UsbDevice findCamera(UsbManager mgr) {
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.stop();
        if (usb != null) usb.stop();
        if (decoder != null) decoder.stop();
        if (webRtcSurface != null) webRtcSurface.release();
    }

    private void log(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> { tvLog.append(s); tvLog.append("\n"); });
    }
}