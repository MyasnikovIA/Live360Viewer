package ru.miacomsoft.live360viewer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
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
    private static final String PHOTO_SUBDIR = "Live360Viewer";
    private static final long MIN_FREE_SPACE_BYTES = 10L * 1024 * 1024;
    private static final int MENU_SHARE_LAST = 100;

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

    private volatile Uri  lastSavedUri  = null;
    private volatile File lastSavedFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent in = getIntent();
        if (in != null && in.hasExtra(EXTRA_VIEW_MODE)) {
            viewMode = in.getIntExtra(EXTRA_VIEW_MODE, PhotoSettings.DEFAULT_VIEW_MODE);
        } else {
            viewMode = PhotoSettings.getViewMode(this);
        }

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
            flatRenderer.setOnSurfaceReadyListener(st -> runOnUiThread(() -> startCamera(st)));
            glSurface.setRenderer(flatRenderer);
        } else {
            sphereRenderer = new SphereRenderer();
            sphereRenderer.setOnSurfaceReadyListener(st -> runOnUiThread(() -> startCamera(st)));
            glSurface.setRenderer(sphereRenderer);
        }
        glSurface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

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
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_SHARE_LAST, Menu.NONE, "Отправить последнее фото");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem share = menu.findItem(MENU_SHARE_LAST);
        if (share != null) share.setEnabled(lastSavedUri != null || (lastSavedFile != null && lastSavedFile.exists()));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SHARE_LAST) {
            shareLastPhoto();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onResume() { super.onResume(); glSurface.onResume(); }
    @Override protected void onPause() { super.onPause(); glSurface.onPause(); stopCamera(); }
    @Override protected void onStop() { super.onStop(); if (cameraStarted) stopCamera(); }
    @Override protected void onDestroy() { super.onDestroy(); stopCamera(); }

    private void startCamera(SurfaceTexture st) {
        if (st == null) return;
        if (cameraStarted) {
            if (decoder != null) {
                Surface newS = new Surface(st);
                try { decoder.updateSurface(newS); surface = newS; } catch (IOException ignored) {}
            }
            return;
        }

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) { overlay.setText("No USB"); return; }
        UsbDevice cam = findCamera(mgr);
        if (cam == null) { overlay.setText("Camera not connected"); return; }

        cameraStarted = true;
        running = true;
        frameCount = 0;

        surface = new Surface(st);
        decoder = new H264Decoder(surface, VIDEO_W, VIDEO_H);
        try {
            decoder.start();
            decoderReady.set(true);
        } catch (IOException e) {
            cameraStarted = false;
            running = false;
            overlay.setText("Decoder start failed");
            return;
        }

        final int photoRes = PhotoSettings.getResolution(this);
        final int videoRes = PhotoSettings.getVideoResolution(this);

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
                if (!cameraStarted) return;
                session = new LiveViewSession(conn, in, out, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264Decoder d = decoder;
                                if (d != null) d.feed(h264, ptsUs);
                                int fc = ++frameCount;
                                if (fc % 30 == 0) runOnUiThread(() -> overlay.setText("Frames: " + fc));
                            }
                            @Override public void onError(String msg) { Log.e(TAG, "LiveView err: " + msg); }
                        });
                new Thread(() -> {
                    if (!session.start()) runOnUiThread(() -> overlay.setText("Camera start failed"));
                }, "LV-start").start();
            }
            @Override public void onDetached() { stopCamera(); }
            @Override public void onPermissionDenied() { stopCamera(); }
            @Override public void onError(String msg) { stopCamera(); }
        });
    }

    private void stopCamera() {
        cameraStarted = false; running = false; decoderReady.set(false);
        if (session != null) { session.stop(); session = null; }
        if (usb != null) { usb.stop(); usb = null; }
        if (decoder != null) { decoder.stop(); decoder = null; }
        if (surface != null) { surface.release(); surface = null; }
    }

    private UsbDevice findCamera(UsbManager mgr) {
        for (UsbDevice d : mgr.getDeviceList().values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    // ============ ФОТО ============

    private void onTakePhotoClicked() {
        if (session == null || !running) {
            Toast.makeText(this, "Нет соединения", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Проверяем разрешения
        if (!hasStoragePermission()) {
            requestStoragePermission();
            return;
        }

        // 2. Проверяем и создаем каталог
        if (!ensureStorageDirectory()) return;

        // 3. Запускаем съемку
        startPhotoCapture();
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.WRITE_EXTERNAL_STORAGE;

        ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_WRITE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_WRITE_STORAGE) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                onTakePhotoClicked();
            } else {
                Toast.makeText(this, "Без разрешения фото не сохранить", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean ensureStorageDirectory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), PHOTO_SUBDIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "Не удалось создать папку " + dir, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private void startPhotoCapture() {
        btnPhoto.setEnabled(false);
        overlay.setText("📷 Захват…");

        session.takePhoto(new LiveViewSession.PhotoListener() {
            @Override
            public void onPhotoReady(byte[] jpeg, long ts) {
                if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                    runOnUiThread(() -> {
                        btnPhoto.setEnabled(true);
                        overlay.setText("Photo err: invalid JPEG");
                        Toast.makeText(ViewerActivity.this, "Камера вернула невалидный JPEG", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                runOnUiThread(() -> {
                    overlay.setText("💾 Сохранение…");
                    savePhoto(jpeg);
                });
            }

            @Override
            public void onPhotoError(String msg) {
                Log.e(TAG, "photo: onPhotoError " + msg);
                runOnUiThread(() -> {
                    btnPhoto.setEnabled(true);
                    overlay.setText("Photo err: " + msg);
                    Toast.makeText(ViewerActivity.this, "Ошибка фото: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void savePhoto(byte[] jpeg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) savePhotoQ(jpeg);
        else savePhotoLegacy(jpeg);
    }

    private void savePhotoQ(byte[] jpeg) {
        String name = "LIVE360_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + PHOTO_SUBDIR);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("insert() returned null");

            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("openOutputStream() returned null");
                os.write(jpeg);
                os.flush();
            }

            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, publish, null, null);

            lastSavedUri = uri;
            btnPhoto.setEnabled(true);
            overlay.setText("Saved: " + name);
            invalidateOptionsMenu();
            Toast.makeText(this, "Фото сохранено: " + name, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            btnPhoto.setEnabled(true);
            overlay.setText("Save failed: " + e.getMessage());
        }
    }

    private void savePhotoLegacy(byte[] jpeg) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), PHOTO_SUBDIR);
        String name = "LIVE360_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
        final File f = new File(dir, name);

        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(jpeg);
            fos.flush();
        } catch (IOException e) {
            btnPhoto.setEnabled(true);
            overlay.setText("Save failed: " + e.getMessage());
            return;
        }

        lastSavedFile = f;
        MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"},
                (path, uri) -> { if (uri != null) lastSavedUri = uri; runOnUiThread(this::invalidateOptionsMenu); });

        btnPhoto.setEnabled(true);
        overlay.setText("Saved: " + f.getAbsolutePath());
        Toast.makeText(this, "Фото: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
    }

    private void shareLastPhoto() {
        Uri uri = lastSavedUri;
        if (uri == null && lastSavedFile != null && lastSavedFile.exists()) {
            try {
                uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", lastSavedFile);
            } catch (IllegalArgumentException e) { return; }
        }
        if (uri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/jpeg");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Отправить фото"));
    }
}