package ru.miacomsoft.live360viewer;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.miacomsoft.live360viewer.decoder.H264Decoder;
import ru.miacomsoft.live360viewer.render.FlatRenderer;
import ru.miacomsoft.live360viewer.render.SphereRenderer;
import ru.miacomsoft.live360viewer.settings.PhotoSettings;
import ru.miacomsoft.live360viewer.usb.LiveViewSession;
import ru.miacomsoft.live360viewer.usb.ScsiConstants;
import ru.miacomsoft.live360viewer.usb.UsbCameraManager;

public class ViewerActivity extends AppCompatActivity {

    private static final String TAG = "ViewerActivity";
    private static final int REQ_WRITE_STORAGE = 1001;

    public static final String EXTRA_VIEW_MODE = "extra_view_mode";

    private static final int VIDEO_W = 1280;
    private static final int VIDEO_H = 640;

    private GLSurfaceView glSurface;
    private SphereRenderer sphereRenderer;
    private FlatRenderer flatRenderer;
    private int viewMode = PhotoSettings.VIEW_MODE_SPHERE;

    private H264Decoder decoder;
    private LiveViewSession session;
    private UsbCameraManager usb;
    private TextView overlay;
    private Button btnPhoto;

    private volatile boolean running = false;
    private final AtomicBoolean decoderReady = new AtomicBoolean(false);
    private volatile int frameCount = 0;
    private volatile boolean cameraStarted = false;

    private Surface surface;
    private float lastX, lastY;

    private byte[] pendingPhotoJpeg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent in = getIntent();
        if (in != null && in.hasExtra(EXTRA_VIEW_MODE)) {
            viewMode = in.getIntExtra(EXTRA_VIEW_MODE, PhotoSettings.DEFAULT_VIEW_MODE);
        } else {
            viewMode = PhotoSettings.getViewMode(this);
        }
        Log.i(TAG, "viewMode = " + PhotoSettings.labelView(viewMode));

        if (viewMode == PhotoSettings.VIEW_MODE_FLAT) {
            setContentView(R.layout.activity_viewer_flat);
        } else {
            setContentView(R.layout.activity_viewer);
        }

        glSurface = findViewById(R.id.glSurface);
        overlay   = findViewById(R.id.tvOverlay);
        btnPhoto  = findViewById(R.id.btnPhoto);

        btnPhoto.setOnClickListener(v -> onTakePhotoClicked());

        glSurface.setEGLContextClientVersion(2);

        if (viewMode == PhotoSettings.VIEW_MODE_FLAT) {
            flatRenderer = new FlatRenderer();
            flatRenderer.setOnSurfaceReadyListener(st -> {
                Log.i(TAG, "Flat onSurfaceReady: " + st);
                runOnUiThread(() -> startCamera(st));
            });
            glSurface.setRenderer(flatRenderer);
        } else {
            sphereRenderer = new SphereRenderer();
            sphereRenderer.setOnSurfaceReadyListener(st -> {
                Log.i(TAG, "Sphere onSurfaceReady: " + st);
                runOnUiThread(() -> startCamera(st));
            });
            glSurface.setRenderer(sphereRenderer);
        }
        glSurface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Свайп-вращение имеет смысл только для сферы
        if (viewMode == PhotoSettings.VIEW_MODE_SPHERE) {
            glSurface.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = e.getX(); lastY = e.getY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        float dX = (e.getX() - lastX) * 0.2f;
                        float dY = -(e.getY() - lastY) * 0.2f;
                        if (sphereRenderer != null) sphereRenderer.setRotation(dX, dY);
                        lastX = e.getX(); lastY = e.getY(); return true;
                }
                return false;
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurface.onResume();
        logUsbPermissionState();
        // onSurfaceCreated сработает после onResume и вызовет startCamera(st);
        // отдельный рестарт здесь не нужен.
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Сначала гасим рендер — EGL-контекст будет уничтожен.
        glSurface.onPause();
        // Синхронно останавливаем камеру/декодер и сбрасываем cameraStarted.
        // На resume GLSurfaceView пересоздаст контекст → onSurfaceCreated →
        // onSurfaceReady → startCamera(st) со свежим SurfaceTexture.
        stopCamera();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Защита: если между onPause и onStop что-то успело перезапустить
        // камеру (не должно, но onStop должен быть идемпотентным).
        if (cameraStarted) stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
    }

    private void logUsbPermissionState() {
        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) return;
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) {
                Log.i(TAG, "camera present, hasPermission=" + mgr.hasPermission(d));
                return;
            }
        }
        Log.w(TAG, "camera not attached");
    }

    // ============ Камера ============

    /**
     * Единая точка входа: вызывается из onSurfaceReady (UI-поток).
     *
     *  - если камера уже запущена — SurfaceTexture изменился (пересоздание
     *    Activity), меняем Surface у существующего H264Decoder через
     *    updateSurface();
     *  - иначе — полный запуск.
     */
    private void startCamera(SurfaceTexture st) {
        if (st == null) {
            Log.w(TAG, "startCamera: null SurfaceTexture");
            return;
        }

        if (cameraStarted) {
            if (decoder != null) {
                Surface newS = new Surface(st);
                try {
                    decoder.updateSurface(newS);
                    Surface old = surface;
                    surface = newS;
                    if (old != null && old != newS) {
                        try { old.release(); } catch (Exception ignored) {}
                    }
                    Log.i(TAG, "startCamera: decoder surface updated");
                } catch (IOException e) {
                    Log.e(TAG, "updateSurface failed: " + e.getMessage(), e);
                    try { newS.release(); } catch (Exception ignored) {}
                }
            } else {
                Log.w(TAG, "startCamera: cameraStarted but decoder==null, ignoring");
            }
            return;
        }

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) {
            overlay.setText("No USB");
            return;
        }
        UsbDevice cam = findCamera(mgr);
        if (cam == null) {
            overlay.setText("Camera not connected");
            return;
        }

        cameraStarted = true;
        running = true;
        frameCount = 0;

        surface = new Surface(st);
        decoder = new H264Decoder(surface, VIDEO_W, VIDEO_H);
        try {
            decoder.start();
            decoderReady.set(true);
        } catch (IOException e) {
            Log.e(TAG, "decoder.start failed: " + e.getMessage(), e);
            cameraStarted = false;
            running = false;
            try { surface.release(); } catch (Exception ignored) {}
            surface = null;
            decoder = null;
            overlay.setText("Decoder start failed");
            return;
        }

        final int photoRes = PhotoSettings.getResolution(this);
        final int videoRes = PhotoSettings.getVideoResolution(this);

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                                    UsbEndpoint in, UsbEndpoint out) {
                if (!cameraStarted) return;
                session = new LiveViewSession(conn, in, out, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override
                            public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264Decoder d = decoder;
                                if (d != null) d.feed(h264, ptsUs);
                                int fc = ++frameCount;
                                if (fc % 30 == 0) {
                                    runOnUiThread(() -> {
                                        if (overlay != null) overlay.setText("Frames: " + fc);
                                    });
                                }
                            }
                            @Override
                            public void onError(String msg) {
                                Log.e(TAG, "LiveView error: " + msg);
                            }
                        });
                new Thread(() -> {
                    boolean ok = session.start();
                    if (!ok) {
                        Log.e(TAG, "session.start failed");
                        runOnUiThread(() -> {
                            if (overlay != null) overlay.setText("Camera start failed");
                        });
                    }
                }, "LV-start").start();
            }

            @Override
            public void onDetached() {
                Log.w(TAG, "camera detached");
                stopCamera();
            }

            @Override
            public void onPermissionDenied() {
                Log.w(TAG, "USB permission denied");
                stopCamera();
                runOnUiThread(() -> Toast.makeText(ViewerActivity.this,
                        "USB permission denied", Toast.LENGTH_LONG).show());
            }

            @Override
            public void onError(String msg) {
                Log.e(TAG, "USB error: " + msg);
                stopCamera();
            }
        });
    }

    /**
     * Полная синхронная остановка камеры. Идемпотентна.
     * Сбрасывает cameraStarted в false — для корректного перезапуска после
     * onPause/пересоздания Activity.
     */
    private void stopCamera() {
        cameraStarted = false;
        running = false;
        decoderReady.set(false);

        LiveViewSession s = session;
        session = null;
        if (s != null) {
            try { s.stop(); }
            catch (Exception e) { Log.w(TAG, "session.stop: " + e.getMessage()); }
        }

        UsbCameraManager u = usb;
        usb = null;
        if (u != null) {
            try { u.stop(); }
            catch (Exception e) { Log.w(TAG, "usb.stop: " + e.getMessage()); }
        }

        H264Decoder d = decoder;
        decoder = null;
        if (d != null) {
            try { d.stop(); }
            catch (Exception e) { Log.w(TAG, "decoder.stop: " + e.getMessage()); }
        }

        Surface sf = surface;
        surface = null;
        if (sf != null) {
            try { sf.release(); }
            catch (Exception e) { Log.w(TAG, "surface.release: " + e.getMessage()); }
        }
    }

    private UsbDevice findCamera(UsbManager mgr) {
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    // ============ Фото ============

    private void onTakePhotoClicked() {
        final LiveViewSession s = session;
        if (s == null || !running) {
            overlay.setText("Not connected");
            Toast.makeText(this, "Нет соединения", Toast.LENGTH_SHORT).show();
            return;
        }
        btnPhoto.setEnabled(false);
        overlay.setText("📷 Фото…");
        Log.i(TAG, "photo: clicked, calling takePhoto()");

        s.takePhoto(new LiveViewSession.PhotoListener() {
            @Override public void onPhotoReady(byte[] jpeg, long ts) {
                Log.i(TAG, "photo: onPhotoReady size=" + jpeg.length);
                runOnUiThread(() -> {
                    btnPhoto.setEnabled(true);
                    savePhotoWithPermission(jpeg);
                });
            }
            @Override public void onPhotoError(String msg) {
                Log.e(TAG, "photo: onPhotoError " + msg);
                runOnUiThread(() -> {
                    btnPhoto.setEnabled(true);
                    overlay.setText("Photo err: " + msg);
                });
            }
        });
    }

    private void savePhotoWithPermission(byte[] jpeg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePhotoQ(jpeg);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPhotoJpeg = jpeg;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE);
            return;
        }
        savePhotoLegacy(jpeg);
    }

    private void savePhotoQ(byte[] jpeg) {
        String name = "LIVE360_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Live360Viewer");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            overlay.setText("Save failed: uri null");
            return;
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("openOutputStream null");
            os.write(jpeg);
            os.flush();
            overlay.setText("Saved: " + name);
            Toast.makeText(this, "Фото сохранено: " + name, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "savePhotoQ: " + e.getMessage(), e);
            overlay.setText("Save failed: " + e.getMessage());
        }
    }

    private void savePhotoLegacy(byte[] jpeg) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Live360Viewer");
        if (!dir.exists() && !dir.mkdirs()) {
            overlay.setText("Save failed: mkdirs");
            return;
        }
        String name = "LIVE360_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".jpg";
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(jpeg);
            fos.flush();
            overlay.setText("Saved: " + f.getAbsolutePath());
            Toast.makeText(this, "Фото: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "savePhotoLegacy: " + e.getMessage(), e);
            overlay.setText("Save failed: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_WRITE_STORAGE) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED
                && pendingPhotoJpeg != null) {
            byte[] j = pendingPhotoJpeg;
            pendingPhotoJpeg = null;
            savePhotoLegacy(j);
        } else {
            pendingPhotoJpeg = null;
            Toast.makeText(this, "Без разрешения фото не сохранить",
                    Toast.LENGTH_LONG).show();
        }
    }
}