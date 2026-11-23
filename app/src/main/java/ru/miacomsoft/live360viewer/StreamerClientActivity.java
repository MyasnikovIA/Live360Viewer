package ru.miacomsoft.live360viewer;

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

public class StreamerClientActivity extends AppCompatActivity {

    private static final String TAG = "StreamerClient";
    private static final String PREFS = "stream_client_settings";
    private static final String KEY_ADDR = "server_address";

    private EditText etAddress;
    private TextView tvStatus, tvLog, tvNetworkInfo;
    private Button btnStart, btnStop;

    private H264StreamClient client;
    private UsbCameraManager usb;
    private LiveViewSession session;
    private volatile boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streamer_client);

        etAddress = findViewById(R.id.etAddress);
        tvStatus  = findViewById(R.id.tvStatus);
        tvLog     = findViewById(R.id.tvLog);
        btnStart  = findViewById(R.id.btnStart);
        btnStop   = findViewById(R.id.btnStop);

        // Если вы добавили TextView в layout для инфы о сети:
        // tvNetworkInfo = findViewById(R.id.tvNetworkInfo);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etAddress.setText(sp.getString(KEY_ADDR, "192.168.1.100:8888"));

        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnStop.setEnabled(false);

        showNetworkInfo();
    }

    private void showNetworkInfo() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                String ssid = wm.getConnectionInfo().getSSID();
                String info = "Телефон IP: " + ip + " | Wi-Fi: " + ssid;
                Log.i(TAG, "Сетевая информация: " + info);
                // Если есть tvNetworkInfo, можно вывести туда:
                // if (tvNetworkInfo != null) tvNetworkInfo.setText(info);
                log(info);
            } else {
                log("Wi-Fi выключен или не подключен. IP: " + getLocalIpAddress());
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка получения сетевой инфы: " + e.getMessage());
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

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        etAddress.setEnabled(false);

        client = new H264StreamClient(host, port);
        client.setListener(new H264StreamClient.Listener() {
            @Override
            public void onConnecting(String h, int p) {
                runOnUiThread(() -> {
                    tvStatus.setText("Подключение к " + h + ":" + p + "…");
                    log("Попытка подключения к " + h + ":" + p);
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
                runOnUiThread(() -> {
                    tvStatus.setText("Отключено: " + reason);
                    log("Отключено: " + reason);
                });
                stopCamera();
                runOnUiThread(() -> {
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                    etAddress.setEnabled(true);
                });
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    tvStatus.setText("Ошибка: " + msg);
                    log("ОШИБКА: " + msg);
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                    etAddress.setEnabled(true);
                });
            }
        });
        client.start();
    }

    private void stopStreaming() {
        stopCamera();
        H264StreamClient c = client;
        client = null;
        if (c != null) new Thread(c::stop, "client-stop").start();
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        etAddress.setEnabled(true);
        tvStatus.setText("Остановлено");
    }

    private void startCamera() {
        if (streaming) return;
        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) { kickClient("UsbManager unavailable"); return; }
        UsbDevice cam = findCamera(mgr);
        if (cam == null) { kickClient("Camera not connected"); return; }

        streaming = true;
        final int photoRes = PhotoSettings.getResolution(this);

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                                    UsbEndpoint in, UsbEndpoint out) {
                if (!streaming) return;
                session = new LiveViewSession(conn, in, out, photoRes,
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
                            tvStatus.setText("Трансляция активна");
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

    private void stopCamera() {
        streaming = false;
        final LiveViewSession s = session; session = null;
        if (s != null) new Thread(s::stop, "LV-stop").start();
        final UsbCameraManager u = usb; usb = null;
        if (u != null) new Thread(u::stop, "USB-stop").start();
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