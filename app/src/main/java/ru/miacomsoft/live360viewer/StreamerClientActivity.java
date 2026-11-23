package ru.miacomsoft.live360viewer;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.HashMap;
import java.util.List;

import ru.miacomsoft.live360viewer.audio.AudioStreamSource;
import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.stream.H264StreamClient;
import ru.miacomsoft.live360viewer.stream.TsMuxer;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

/**
 * TCP-клиент: отправляет MPEG-TS (видео H.264 + звук AAC) на один порт.
 * Сервер: `vlc tcp/ts://<ip>:<port>` или приёмник TS.
 */
public class StreamerClientActivity extends AppCompatActivity {

    private static final String TAG = "StreamerClient";
    private static final String PREFS = "stream_client_settings";
    private static final String KEY_ADDR = "server_address";
    private static final int REQ_RECORD_AUDIO = 2002;

    private EditText etAddress;
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;

    private H264StreamClient client;
    private AudioStreamSource audioSource;
    private UsbCameraManager usb;
    private LiveViewSession session;
    private volatile boolean streaming = false;

    private final Object cameraLock = new Object();
    private final TsMuxer tsMuxer = new TsMuxer();
    private final Object tsLock = new Object();
    private volatile boolean userStopped = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer_client);

        etAddress = findViewById(R.id.etAddress);
        tvStatus  = findViewById(R.id.tvStatus);
        tvLog     = findViewById(R.id.tvLog);
        btnStart  = findViewById(R.id.btnStart);
        btnStop   = findViewById(R.id.btnStop);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etAddress.setText(sp.getString(KEY_ADDR, "192.168.1.100:8888"));

        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnStop.setEnabled(false);

        ensureAudioPermission();
    }

    private void ensureAudioPermission() {
        if (AudioStreamSource.hasPermission(this)) return;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) return;
        boolean ok = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        log("RECORD_AUDIO granted=" + ok);
        if (!ok) Toast.makeText(this, "Без микрофона — только видео",
                Toast.LENGTH_LONG).show();
    }

    private void startStreaming() {
        String addr = etAddress.getText().toString().trim();
        int colon = addr.lastIndexOf(':');
        if (colon <= 0 || colon >= addr.length() - 1) {
            Toast.makeText(this, "Неверный формат host:port", Toast.LENGTH_LONG).show();
            return;
        }
        String host = addr.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(addr.substring(colon + 1));
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Неверный порт", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ADDR, addr).apply();

        userStopped = false;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        etAddress.setEnabled(false);

        client = new H264StreamClient(host, port);
        client.setListener(new H264StreamClient.Listener() {
            @Override public void onConnecting(String h, int p) {
                runOnUiThread(() -> log("Connecting to " + h + ":" + p));
            }
            @Override public void onConnected(String remote) {
                runOnUiThread(() -> log("Connected to " + remote));
                startCamera();
            }
            @Override public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    if (!userStopped) tvStatus.setText("Переподключение…");
                    log("Disconnected: " + reason);
                });
                stopCamera();
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> log("Error: " + msg));
            }
            @Override public void onReconnecting(String h, int p, int attempt, long delayMs) {
                runOnUiThread(() -> log("Reconnect #" + attempt + " через " + delayMs + " мс"));
            }
            @Override public void onReconnectGaveUp(String lastError) {
                runOnUiThread(() -> { log("Gave up: " + lastError); stopStreaming(); });
            }
        });
        client.start();
    }

    private void stopStreaming() {
        userStopped = true;
        stopCamera();
        H264StreamClient c = client; client = null;
        if (c != null) new Thread(c::stop, "client-stop").start();
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        etAddress.setEnabled(true);
        tvStatus.setText("Остановлено");
    }

    private void startCamera() {
        synchronized (cameraLock) {
            if (streaming) return;
            UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
            if (mgr == null) { kickClient("UsbManager unavailable"); return; }
            UsbDevice cam = findCamera(mgr);
            if (cam == null) { kickClient("Camera not connected"); return; }

            streaming = true;
            final int photoRes = PhotoSettings.getResolution(this);
            final int videoRes = PhotoSettings.getVideoResolution(this);

            usb = new UsbCameraManager(this);
            usb.start(new UsbCameraManager.Listener() {
                @Override
                public void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                                        UsbEndpoint in, UsbEndpoint out) {
                    if (!streaming) return;
                    session = new LiveViewSession(conn, in, out, photoRes, videoRes,
                            new LiveViewSession.FrameListener() {
                                @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                    synchronized (tsLock) {
                                        tsMuxer.writeVideo(h264, ptsUs);
                                        pushTs();
                                    }
                                }
                                @Override public void onError(String msg) {
                                    Log.e(TAG, "LiveView error: " + msg);
                                }
                            });
                    new Thread(() -> {
                        boolean ok = session.start();
                        if (ok) startAudio();
                        runOnUiThread(() -> log(ok
                                ? "Камера запущена, MPEG-TS отправляется"
                                : "Не удалось запустить камеру"));
                    }, "LV-start").start();
                }
                @Override public void onDetached() {
                    log("Камера отключена");
                    streaming = false;
                    kickClient("Camera detached");
                }
                @Override public void onPermissionDenied() {
                    log("USB permission denied");
                    streaming = false;
                    kickClient("USB permission denied");
                }
                @Override public void onError(String msg) {
                    log("USB: " + msg);
                    streaming = false;
                    kickClient("USB: " + msg);
                }
            });
        }
    }

    private void stopCamera() {
        synchronized (cameraLock) {
            streaming = false;
            stopAudio();
            final LiveViewSession s = session; session = null;
            if (s != null) {
                try { s.stop(); }
                catch (Exception e) { Log.w(TAG, "session.stop: " + e.getMessage()); }
            }
            final UsbCameraManager u = usb; usb = null;
            if (u != null) {
                try { u.stop(); }
                catch (Exception e) { Log.w(TAG, "usb.stop: " + e.getMessage()); }
            }
        }
    }

    private void startAudio() {
        if (audioSource != null) return;
        if (!AudioStreamSource.hasPermission(this)) {
            log("Audio: no RECORD_AUDIO — только видео");
            return;
        }
        audioSource = new AudioStreamSource(this, new AudioStreamSource.Listener() {
            @Override
            public void onAudioFrame(byte[] adtsFrame, long ptsUs) {
                synchronized (tsLock) {
                    tsMuxer.writeAudio(adtsFrame, ptsUs);
                    pushTs();
                }
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> log("Audio error: " + msg));
            }
        });
        boolean ok = audioSource.start();
        log(ok ? "Audio capture started" : "Audio capture failed");
    }

    private void stopAudio() {
        AudioStreamSource a = audioSource;
        audioSource = null;
        if (a != null) new Thread(a::stop, "audio-stop").start();
    }

    private void pushTs() {
        H264StreamClient c = client;
        if (c == null) { tsMuxer.drain(); return; }
        List<byte[]> packets = tsMuxer.drain();
        for (byte[] p : packets) c.sendFrame(p);
    }

    private void kickClient(String reason) {
        H264StreamClient c = client;
        if (c != null) c.stop();
    }

    private UsbDevice findCamera(UsbManager mgr) {
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        userStopped = true;
        stopCamera();
        H264StreamClient c = client; client = null;
        if (c != null) c.stop();
    }

    private void log(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> { tvLog.append(s); tvLog.append("\n"); });
    }
}