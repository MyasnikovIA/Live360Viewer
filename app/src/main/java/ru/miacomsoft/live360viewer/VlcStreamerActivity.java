package ru.miacomsoft.live360viewer;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;

import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.stream.H264StreamServer;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

/**
 * Старый добрый TCP-сервер для VLC (только видео, без звука).
 *
 * VLC: tcp/h264://192.168.131.4:8888
 */
public class VlcStreamerActivity extends AppCompatActivity {

    private static final String TAG = "VlcStreamer";
    private static final int PORT = 8888;

    private TextView tvStatus;
    private TextView tvLog;

    private H264StreamServer server;
    private UsbCameraManager usb;
    private LiveViewSession session;

    private volatile boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer); // Используем тот же layout, что и для WebRTC

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        Button btnStop = findViewById(R.id.btnStop);

        // Прячем кнопку изменения разрешения, так как в этой активити мы её не используем
        Button btnChangeRes = findViewById(R.id.btnChangeRes);
        if (btnChangeRes != null) btnChangeRes.setVisibility(android.view.View.GONE);

        btnStop.setOnClickListener(v -> finish());

        startServer();
    }

    private void startServer() {
        server = new H264StreamServer(PORT, false);
        server.setListener(new H264StreamServer.Listener() {
            @Override
            public void onListening(String ip, int p) {
                runOnUiThread(() -> {
                    tvStatus.setText("VLC Сервер ожидает на\n" + ip + ":" + p);
                    log("Listening on " + ip + ":" + p);
                });
            }

            @Override
            public void onClientConnected(String addr) {
                runOnUiThread(() -> {
                    tvStatus.setText("VLC подключен\nЗапуск камеры...");
                    log("VLC connected: " + addr);
                });
                startCamera();
            }

            @Override
            public void onClientDisconnected() {
                runOnUiThread(() -> {
                    tvStatus.setText("VLC отключен. Ожидание...");
                    log("VLC disconnected");
                });
                stopCamera();
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    tvStatus.setText("Ошибка: " + msg);
                    log("Server error: " + msg);
                });
            }
        });
        server.start();
    }

    private void startCamera() {
        if (streaming) return;

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) {
            log("UsbManager unavailable");
            kickClient("UsbManager unavailable");
            return;
        }
        UsbDevice cam = findCamera(mgr);
        if (cam == null) {
            log("Camera not found");
            kickClient("Camera not connected");
            return;
        }

        streaming = true;
        final int photoRes = PhotoSettings.getResolution(this);
        final int videoRes = PhotoSettings.getVideoResolution(this);
        Log.i(TAG, "starting camera, photoRes=0x" + Integer.toHexString(photoRes)
                + " videoRes=0x" + Integer.toHexString(videoRes));

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                                    UsbEndpoint in, UsbEndpoint out) {
                if (!streaming) return;
                session = new LiveViewSession(conn, in, out, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override
                            public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264StreamServer s = server;
                                if (s != null) s.sendFrame(h264);
                            }
                            @Override
                            public void onError(String msg) {
                                Log.e(TAG, "LiveView error: " + msg);
                            }
                        });
                new Thread(() -> {
                    boolean ok = session.start();
                    runOnUiThread(() -> {
                        if (ok) {
                            tvStatus.setText("Трансляция активна (H.264)");
                            log("Streaming started");
                        } else {
                            tvStatus.setText("Не удалось запустить камеру");
                            log("LiveView start failed");
                            kickClient("LiveView start failed");
                        }
                    });
                }, "LV-start").start();
            }

            @Override
            public void onDetached() {
                log("Camera detached");
                streaming = false;
                kickClient("Camera detached");
            }

            @Override
            public void onPermissionDenied() {
                log("USB permission denied");
                streaming = false;
                kickClient("USB permission denied");
            }

            @Override
            public void onError(String msg) {
                log("USB: " + msg);
                streaming = false;
                kickClient("USB: " + msg);
            }
        });
    }

    private void stopCamera() {
        streaming = false;
        final LiveViewSession s = session; session = null;
        if (s != null) new Thread(s::stop, "LV-stop").start();
        final UsbCameraManager u = usb; usb = null;
        if (u != null) new Thread(u::stop, "USB-stop").start();
    }

    private void kickClient(String reason) {
        Log.w(TAG, "kick client: " + reason);
        H264StreamServer s = server;
        if (s != null) s.disconnectClient();
    }

    private UsbDevice findCamera(UsbManager mgr) {
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) {
                return d;
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        H264StreamServer s = server; server = null;
        if (s != null) s.stop();
    }

    private void log(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> {
            tvLog.append(s);
            tvLog.append("\n");
        });
    }
}