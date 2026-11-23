package ru.miacomsoft.live360viewer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;

import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "ru.miacomsoft.live360viewer.USB_PERMISSION";

    private TextView tvStatus, tvLog;
    private Spinner spPhotoRes;
    private Spinner spVideoRes;

    private PendingIntent permIntent;
    private boolean isReceiverRegistered = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        log("USB permission GRANTED for device: " + device.getDeviceName());
                        tvStatus.setText("Camera detected and permitted. Tap Start");
                    }
                } else {
                    log("USB permission DENIED by user");
                    tvStatus.setText("USB permission denied");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus   = findViewById(R.id.tvStatus);
        tvLog      = findViewById(R.id.tvLog);
        spPhotoRes = findViewById(R.id.spPhotoRes);
        spVideoRes = findViewById(R.id.spVideoRes);

        Button btnConnect     = findViewById(R.id.btnConnect);
        Button btnStartSphere = findViewById(R.id.btnStartSphere);
        Button btnStartFlat   = findViewById(R.id.btnStartFlat);
        Button btnStartWebRtc = findViewById(R.id.btnStartWebRtc);   // ★ WebRTC
        Button btnStartVlc    = findViewById(R.id.btnStartVlc);      // ★ VLC H.264
        Button btnStartClient = findViewById(R.id.btnStartClient);
        Button btnStop        = findViewById(R.id.btnStop);

        setupPhotoResSpinner();
        setupVideoResSpinner();

        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        permIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        isReceiverRegistered = true;

        btnConnect.setOnClickListener(v -> {
            log("Check camera");
            tvStatus.setText(R.string.status_connecting);
            checkCameraPresence();
        });

        btnStartSphere.setOnClickListener(v -> {
            int photoRes = PhotoSettings.getResolution(this);
            PhotoSettings.setViewMode(this, PhotoSettings.VIEW_MODE_SPHERE);
            log("Opening SPHERE viewer (photo_res=0x" + Integer.toHexString(photoRes)
                    + ", video_res=0x" + Integer.toHexString(
                    PhotoSettings.getVideoResolution(this)) + ")");
            Intent i = new Intent(this, ViewerActivity.class);
            i.putExtra(ViewerActivity.EXTRA_VIEW_MODE, PhotoSettings.VIEW_MODE_SPHERE);
            startActivity(i);
        });

        btnStartFlat.setOnClickListener(v -> {
            int photoRes = PhotoSettings.getResolution(this);
            PhotoSettings.setViewMode(this, PhotoSettings.VIEW_MODE_FLAT);
            log("Opening FLAT viewer (photo_res=0x" + Integer.toHexString(photoRes)
                    + ", video_res=0x" + Integer.toHexString(
                    PhotoSettings.getVideoResolution(this)) + ")");
            Intent i = new Intent(this, ViewerActivity.class);
            i.putExtra(ViewerActivity.EXTRA_VIEW_MODE, PhotoSettings.VIEW_MODE_FLAT);
            startActivity(i);
        });

        btnStartWebRtc.setOnClickListener(v -> {
            log("Opening WebRTC server (browser)");
            startActivity(new Intent(this, StreamerActivity.class));
        });

        btnStartVlc.setOnClickListener(v -> {
            log("Opening VLC server (raw H.264 over TCP)");
            startActivity(new Intent(this, VlcStreamerActivity.class));
        });

        btnStartClient.setOnClickListener(v -> {
            log("Opening STREAM CLIENT (send raw H.264 to remote)");
            startActivity(new Intent(this, StreamerClientActivity.class));
        });

        btnStop.setOnClickListener(v -> {
            log("Stop");
            tvStatus.setText(R.string.status_idle);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isReceiverRegistered) {
            unregisterReceiver(usbReceiver);
            isReceiverRegistered = false;
        }
    }

    private void setupPhotoResSpinner() {
        final int[] codes = PhotoSettings.allCodes();
        String[] labels = new String[codes.length];
        for (int i = 0; i < codes.length; i++) labels[i] = PhotoSettings.label(codes[i]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPhotoRes.setAdapter(adapter);

        int saved = PhotoSettings.getResolution(this);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == saved) { spPhotoRes.setSelection(i); break; }
        }

        spPhotoRes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                int code = codes[pos];
                int prev = PhotoSettings.getResolution(MainActivity.this);
                PhotoSettings.setResolution(MainActivity.this, code);
                log("Photo resolution → " + PhotoSettings.label(code)
                        + " (0x" + Integer.toHexString(code) + ")");
                if (prev != code) {
                    Toast.makeText(MainActivity.this,
                            "Применится при следующем запуске трансляции",
                            Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void setupVideoResSpinner() {
        final int[] codes = PhotoSettings.allVideoCodes();
        String[] labels = new String[codes.length];
        for (int i = 0; i < codes.length; i++) labels[i] = PhotoSettings.labelVideo(codes[i]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVideoRes.setAdapter(adapter);

        int saved = PhotoSettings.getVideoResolution(this);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == saved) { spVideoRes.setSelection(i); break; }
        }

        spVideoRes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                int code = codes[pos];
                int prev = PhotoSettings.getVideoResolution(MainActivity.this);
                PhotoSettings.setVideoResolution(MainActivity.this, code);
                log("Video resolution → " + PhotoSettings.labelVideo(code)
                        + " (0x" + Integer.toHexString(code) + ")");
                if (prev != code) {
                    Toast.makeText(MainActivity.this,
                            "Применится при следующем запуске трансляции",
                            Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void checkCameraPresence() {
        new Thread(() -> {
            UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
            if (mgr == null) {
                runOnUiThread(() -> {
                    log("No UsbManager");
                    tvStatus.setText(R.string.status_idle);
                });
                return;
            }

            HashMap<String, UsbDevice> list = mgr.getDeviceList();
            UsbDevice found = null;
            for (UsbDevice d : list.values()) {
                Log.d(TAG, "USB vid=" + d.getVendorId() + " pid=" + d.getProductId());
                if (d.getVendorId() == ScsiConstants.VENDOR_ID
                        && d.getProductId() == ScsiConstants.PRODUCT_ID) {
                    found = d;
                    break;
                }
            }

            if (found == null) {
                runOnUiThread(() -> {
                    log("Camera not found");
                    tvStatus.setText(R.string.status_idle);
                });
                return;
            }

            final UsbDevice finalFound = found;
            if (!mgr.hasPermission(finalFound)) {
                runOnUiThread(() -> {
                    log("Camera found, hasPermission=false. Requesting permission...");
                    tvStatus.setText("Requesting USB permission...");
                });
                mgr.requestPermission(finalFound, permIntent);
            } else {
                runOnUiThread(() -> {
                    log("Camera found, hasPermission=true");
                    tvStatus.setText("Camera detected. Tap Start");
                });
            }
        }).start();
    }

    private void log(String s) {
        Log.d(TAG, s);
        runOnUiThread(() -> {
            tvLog.append(s); tvLog.append("\n");
            int scroll = tvLog.getLayout() != null
                    ? tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight()
                    : 0;
            if (scroll > 0) tvLog.scrollTo(0, scroll);
        });
    }
}