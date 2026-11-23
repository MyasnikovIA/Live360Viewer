package ru.miacomsoft.live360viewer;

import android.app.AlertDialog;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;

import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.stream.H264StreamServer;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

/**
 * Экран трансляции (TCP-сервер).
 *
 * Сценарий:
 *   1) onCreate — запускается TCP-сервер и ждёт клиента.
 *   2) onClientConnected — стартует USB-камера (LiveViewSession).
 *   3) onVideoFrame     — сырые Annex-B кадры уходят прямо в сокет.
 *   4) onClientDisconnected — камера останавливается, сервер снова ждёт.
 *   5) При выходе из Activity — всё останавливается.
 *
 * Кнопка "Video res…" позволяет менять video_res на лету через
 * LiveViewSession.changeVideoRes(): STOP_LIVE_VIEW → SET_ALL_SETTINGS
 * → START_LIVE_VIEW. H264StreamServer кэширует SPS/PPS и отдаст новый IDR.
 */
public class StreamerActivity extends AppCompatActivity {

    private static final String TAG = "StreamerActivity";

    public static final String EXTRA_PORT = "extra_port";
    public static final int DEFAULT_PORT = 8888;

    private TextView tvStatus;
    private TextView tvLog;
    private Button btnChangeRes;

    private H264StreamServer server;
    private UsbCameraManager usb;
    private LiveViewSession session;

    private int port;
    private volatile boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer);

        tvStatus     = findViewById(R.id.tvStatus);
        tvLog        = findViewById(R.id.tvLog);
        btnChangeRes = findViewById(R.id.btnChangeRes);
        Button btnStop = findViewById(R.id.btnStop);

        port = getIntent().getIntExtra(EXTRA_PORT, DEFAULT_PORT);

        btnStop.setOnClickListener(v -> finish());
        btnChangeRes.setOnClickListener(v -> showVideoResDialog());

        startServer();
    }

    // ============ Сервер ============

    private void startServer() {
        server = new H264StreamServer(port);
        server.setListener(new H264StreamServer.Listener() {
            @Override
            public void onListening(String ip, int p) {
                runOnUiThread(() -> {
                    tvStatus.setText("Ожидание подключения\n" + ip + ":" + p);
                    log("Listening on " + ip + ":" + p);
                });
            }

            @Override
            public void onClientConnected(String addr) {
                runOnUiThread(() -> {
                    tvStatus.setText("Клиент: " + addr + "\nЗапуск камеры…");
                    log("Client connected: " + addr);
                });
                startCamera();
            }

            @Override
            public void onClientDisconnected() {
                runOnUiThread(() -> {
                    tvStatus.setText("Клиент отключён. Ожидание…");
                    log("Client disconnected");
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

    // ============ Камера ============

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
                if (!streaming) {
                    Log.w(TAG, "client gone during camera setup — abort");
                    return;
                }
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
                            tvStatus.setText("Трансляция активна (video_res=0x"
                                    + Integer.toHexString(videoRes) + ")");
                            log("Streaming started, video_res=0x"
                                    + Integer.toHexString(videoRes));
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
                runOnUiThread(() ->
                        Toast.makeText(StreamerActivity.this,
                                "USB-разрешение отклонено", Toast.LENGTH_LONG).show());
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

    /** Разрывает соединение с клиентом — тот увидит закрытие сокета и сможет переподключиться. */
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

    // ============ Runtime change video_res ============

    private void showVideoResDialog() {
        final LiveViewSession s = session;
        if (s == null || !streaming) {
            Toast.makeText(this, "Трансляция не активна", Toast.LENGTH_SHORT).show();
            return;
        }

        final int[] codes = PhotoSettings.allVideoCodes();
        String[] labels = new String[codes.length];
        for (int i = 0; i < codes.length; i++) labels[i] = PhotoSettings.labelVideo(codes[i]);

        new AlertDialog.Builder(this)
                .setTitle("Video resolution (live)")
                .setItems(labels, (d, which) -> {
                    final int newRes = codes[which];
                    if (newRes == s.getVideoRes()) {
                        log("changeVideoRes: already 0x" + Integer.toHexString(newRes));
                        return;
                    }
                    log("changeVideoRes → " + PhotoSettings.labelVideo(newRes)
                            + " (0x" + Integer.toHexString(newRes) + ")");
                    new Thread(() -> {
                        boolean ok = s.changeVideoRes(newRes);
                        if (ok) PhotoSettings.setVideoResolution(this, newRes);
                        runOnUiThread(() -> {
                            log("changeVideoRes result=" + ok);
                            if (ok) {
                                tvStatus.setText("Трансляция активна (video_res=0x"
                                        + Integer.toHexString(newRes) + ")");
                            } else {
                                Toast.makeText(this,
                                        "Не удалось сменить video_res",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }, "video-res-change").start();
                })
                .show();
    }

    // ============ Lifecycle ============

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