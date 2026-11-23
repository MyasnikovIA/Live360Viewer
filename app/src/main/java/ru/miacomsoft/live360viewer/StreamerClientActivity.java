package ru.miacomsoft.live360viewer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;

import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.stream.H264StreamClient;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

/**
 * TCP-клиент: отправляет сырой H.264 (Annex-B) на удалённый сервер.
 *
 * Автопереподключение с экспоненциальным backoff (см. H264StreamClient):
 *  - onReconnecting показывает статус "Реконнект #N через X мс…";
 *  - onError не трогает кнопки — retry продолжается;
 *  - onReconnectGaveUp возвращает кнопки Start/Stop и гасит камеру;
 *  - onDisconnected гасит камеру, чтобы при реконнекте onConnected её
 *    поднял заново.
 *
 * Кнопка "Video res…" позволяет менять video_res на лету через
 * LiveViewSession.changeVideoRes().
 */
public class StreamerClientActivity extends AppCompatActivity {

    private static final String TAG = "StreamerClient";
    private static final String PREFS = "stream_client_settings";
    private static final String KEY_ADDR = "server_address";

    private EditText etAddress;
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop, btnChangeRes;

    private H264StreamClient client;
    private UsbCameraManager usb;
    private LiveViewSession session;
    private volatile boolean streaming = false;

    /** Сериализует start/stop камеры — устраняет гонку между onDisconnected
     *  (останавливает старую сессию) и onConnected (создаёт новую) при реконнекте. */
    private final Object cameraLock = new Object();

    /** Пользователь нажал Stop → не показываем "reconnect…" и не мигаем кнопками. */
    private volatile boolean userStopped = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer_client);

        etAddress    = findViewById(R.id.etAddress);
        tvStatus     = findViewById(R.id.tvStatus);
        tvLog        = findViewById(R.id.tvLog);
        btnStart     = findViewById(R.id.btnStart);
        btnStop      = findViewById(R.id.btnStop);
        btnChangeRes = findViewById(R.id.btnChangeRes);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etAddress.setText(sp.getString(KEY_ADDR, "192.168.1.100:8888"));

        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnChangeRes.setOnClickListener(v -> showVideoResDialog());
        btnStop.setEnabled(false);

        showNetworkInfo();
    }

    private void showNetworkInfo() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                String ssid = wm.getConnectionInfo().getSSID();
                log("Телефон IP: " + ip + " | Wi-Fi: " + ssid);
            } else {
                log("Wi-Fi выключен или не подключен. IP: " + getLocalIpAddress());
            }
        } catch (Exception e) {
            Log.e(TAG, "network info: " + e.getMessage());
        }
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface intf = en.nextElement();
                java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) { }
        return "Неизвестен";
    }

    // ============ Start / Stop ============

    private void startStreaming() {
        String addr = etAddress.getText().toString().trim();
        int colon = addr.lastIndexOf(':');
        if (colon <= 0 || colon >= addr.length() - 1) {
            Toast.makeText(this, "Неверный формат. Ожидается host:port", Toast.LENGTH_LONG).show();
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
        // Политика по умолчанию: auto-reconnect on, 1s→30s×2, без лимита.
        // client.setBackoffPolicy(500, 10000, 1.5);
        // client.setMaxReconnectAttempts(5);
        // client.setAutoReconnect(true);

        client.setListener(new H264StreamClient.Listener() {
            @Override
            public void onConnecting(String h, int p) {
                runOnUiThread(() -> {
                    tvStatus.setText("Подключение к " + h + ":" + p + "…");
                    log("Попытка подключения к " + h + ":" + p);
                });
            }

            @Override
            public void onReconnecting(String h, int p, int attempt, long delayMs) {
                runOnUiThread(() -> {
                    tvStatus.setText("Реконнект #" + attempt
                            + " к " + h + ":" + p
                            + " через " + delayMs + " мс…");
                    log("Реконнект #" + attempt + " через " + delayMs + " мс");
                });
            }

            @Override
            public void onConnected(String remote) {
                runOnUiThread(() -> {
                    tvStatus.setText("Подключено к " + remote + "\nЗапуск камеры…");
                    log("УСПЕХ: Подключено к " + remote);
                });
                startCamera();
            }

            @Override
            public void onDisconnected(String reason) {
                // Не трогаем кнопки — реконнект покажет onReconnecting.
                runOnUiThread(() -> {
                    if (!userStopped) {
                        tvStatus.setText("Соединение потеряно: " + reason + "\nПереподключение…");
                    }
                    log("Соединение разорвано: " + reason);
                });
                stopCamera();
            }

            @Override
            public void onError(String msg) {
                // Только лог/статус — будет retry.
                runOnUiThread(() -> {
                    if (!userStopped) {
                        tvStatus.setText("Ошибка: " + msg);
                    }
                    log("ОШИБКА: " + msg);
                });
            }

            @Override
            public void onReconnectGaveUp(String lastError) {
                // Финальная точка: кнопки возвращаем, камеру гасим.
                runOnUiThread(() -> {
                    tvStatus.setText("Не удалось переподключиться: " + lastError);
                    log("СДАЛИСЬ: " + lastError);
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                    etAddress.setEnabled(true);
                });
                stopCamera();
            }
        });
        client.start();
    }

    private void stopStreaming() {
        userStopped = true;
        stopCamera();
        H264StreamClient c = client;
        client = null;
        if (c != null) new Thread(c::stop, "client-stop").start();
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        etAddress.setEnabled(true);
        tvStatus.setText("Остановлено");
    }

    // ============ Камера ============

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
                                    H264StreamClient c = client;
                                    if (c != null) c.sendFrame(h264);
                                }
                                @Override public void onError(String msg) {
                                    Log.e(TAG, "LiveView error: " + msg);
                                }
                            });
                    new Thread(() -> {
                        boolean ok = session.start();
                        runOnUiThread(() -> {
                            if (ok) {
                                tvStatus.setText("Трансляция активна (video_res=0x"
                                        + Integer.toHexString(videoRes) + ")");
                                log("Камера запущена, идет отправка данных");
                            } else {
                                tvStatus.setText("Не удалось запустить камеру");
                                log("Ошибка запуска камеры");
                                kickClient("LiveView start failed");
                            }
                        });
                    }, "LV-start").start();
                }
                @Override public void onDetached() {
                    log("Камера отключена");
                    streaming = false;
                    kickClient("Camera detached");
                }
                @Override public void onPermissionDenied() {
                    log("Отказано в доступе к USB");
                    streaming = false;
                    kickClient("USB permission denied");
                }
                @Override public void onError(String msg) {
                    log("USB Ошибка: " + msg);
                    streaming = false;
                    kickClient("USB: " + msg);
                }
            });
        }
    }

    /**
     * Синхронная остановка под cameraLock: устраняет гонку «старый
     * UsbCameraManager ещё освобождает интерфейс, а новый уже claim».
     */
    private void stopCamera() {
        synchronized (cameraLock) {
            streaming = false;
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
        userStopped = true;
        stopCamera();
        H264StreamClient c = client;
        client = null;
        if (c != null) c.stop();
    }

    private void log(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> {
            tvLog.append(s);
            tvLog.append("\n");
        });
    }
}