package ru.miacomsoft.live360viewer;

import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
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
 * Экран «Клиент»: приложение подключается к удалённому TCP-серверу
 * и отправляет туда сырой H.264 (Annex-B) с USB-камеры.
 *
 * Сценарий:
 *   1) Пользователь вводит host:port и жмёт «Начать трансляцию».
 *   2) Приложение подключается по TCP.
 *   3) После успешного подключения стартует камера.
 *   4) Кадры уходят в сокет без декодирования и без упаковки.
 *   5) При обрыве связи камера останавливается — можно нажать «Начать» снова.
 */
public class StreamerClientActivity extends AppCompatActivity {

    private static final String TAG = "StreamerClient";
    private static final String PREFS = "stream_client_settings";
    private static final String KEY_ADDR = "server_address";

    private EditText etAddress;
    private TextView tvStatus, tvLog;
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

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etAddress.setText(sp.getString(KEY_ADDR, "192.168.1.100:8888"));

        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnStop.setEnabled(false);
    }

    // ============ Старт / стоп ============

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
                    log("Connecting to " + h + ":" + p);
                });
            }
            @Override
            public void onConnected(String remote) {
                runOnUiThread(() -> {
                    tvStatus.setText("Подключено к " + remote + "\nЗапуск камеры…");
                    log("Connected to " + remote);
                });
                startCamera();
            }
            @Override
            public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    tvStatus.setText("Отключено: " + reason);
                    log("Disconnected: " + reason);
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
                    log("Error: " + msg);
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

    // ============ Камера ============

    private void startCamera() {
        if (streaming) return;

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) { kickClient("UsbManager unavailable"); return; }
        UsbDevice cam = findCamera(mgr);
        if (cam == null) { kickClient("Camera not connected"); return; }

        streaming = true;
        final int photoRes = PhotoSettings.getResolution(this);
        Log.i(TAG, "starting camera, photoRes=0x" + Integer.toHexString(photoRes));

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
                            log("Streaming started");
                        } else {
                            tvStatus.setText("Не удалось запустить камеру");
                            log("LiveView start failed");
                            kickClient("LiveView start failed");
                        }
                    });
                }, "LV-start").start();
            }
            @Override public void onDetached() {
                log("Camera detached");
                streaming = false;
                kickClient("Camera detached");
            }
            @Override public void onPermissionDenied() {
                log("USB permission denied");
                streaming = false;
                kickClient("USB permission denied");
                runOnUiThread(() ->
                        Toast.makeText(StreamerClientActivity.this,
                                "USB-разрешение отклонено", Toast.LENGTH_LONG).show());
            }
            @Override public void onError(String msg) {
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