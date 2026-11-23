package ru.miacomsoft.live360viewer;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

    private GLSurfaceView glSurface;
    private SphereRenderer sphereRenderer;
    private FlatRenderer flatRenderer;
    private int viewMode = PhotoSettings.VIEW_MODE_SPHERE;

    private H264Decoder decoder;
    private LiveViewSession session;
    private UsbCameraManager usb;
    private TextView overlay;
    private TextView tvStats;
    private Button btnPhoto;

    private volatile boolean running = false;
    private final AtomicBoolean decoderReady = new AtomicBoolean(false);
    private volatile int frameCount = 0;
    private volatile boolean cameraStarted = false;

    private Surface surface;
    private float lastX, lastY;

    private byte[] pendingPhotoJpeg;

    // ===== Метрики: FPS + реальный размер кадра =====
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable fpsUpdater;
    private int lastFpsFrameCount = 0;
    private long lastFpsTimeMs = 0L;
    private volatile float currentFps = 0f;
    private volatile String resolutionStr = "?×?";

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
        tvStats   = findViewById(R.id.tvStats);
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

        refreshStats();
        startFpsUpdater();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurface.onResume();
        logUsbPermissionState();
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

    // ===== FPS + Превью-разрешение =====

    private void startFpsUpdater() {
        lastFpsFrameCount = 0;
        lastFpsTimeMs = System.currentTimeMillis();
        currentFps = 0f;
        fpsUpdater = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                int delta = frameCount - lastFpsFrameCount;
                long dt = now - lastFpsTimeMs;
                if (dt > 0) {
                    currentFps = delta * 1000f / dt;
                }
                lastFpsFrameCount = frameCount;
                lastFpsTimeMs = now;
                refreshStats();
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.postDelayed(fpsUpdater, 1000);
    }

    private void stopFpsUpdater() {
        if (fpsUpdater != null) {
            uiHandler.removeCallbacks(fpsUpdater);
            fpsUpdater = null;
        }
    }

    private void refreshStats() {
        if (tvStats == null) return;
        tvStats.setText(String.format(Locale.US,
                "Превью: %s · %.1f fps · %d кадров",
                resolutionStr, currentFps, frameCount));
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
            onSaveResult(savePhotoToGalleryQ(jpeg), jpeg);
            return;
        }
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            onSaveResult(savePhotoToGalleryLegacy(jpeg), jpeg);
        } else {
            pendingPhotoJpeg = jpeg;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingPhotoJpeg != null) {
                    onSaveResult(savePhotoToGalleryLegacy(pendingPhotoJpeg), pendingPhotoJpeg);
                    pendingPhotoJpeg = null;
                }
            } else {
                overlay.setText("Нет разрешения на запись");
                Toast.makeText(this, "Для сохранения фото нужно разрешение",
                        Toast.LENGTH_LONG).show();
                pendingPhotoJpeg = null;
            }
        }
    }

    private void onSaveResult(Uri uri, byte[] jpeg) {
        if (uri != null) {
            Log.i(TAG, "saved: " + uri + " (" + jpeg.length + " bytes)");
            overlay.setText("✔ " + uri.getLastPathSegment()
                    + " (" + jpeg.length / 1024 + " КБ)");
            Toast.makeText(this,
                    "Сохранено: " + uri.getLastPathSegment()
                            + " (" + jpeg.length / 1024 + " КБ)\nПуть: " + uri,
                    Toast.LENGTH_LONG).show();
        } else {
            overlay.setText("Save failed");
            Toast.makeText(this, "Не удалось сохранить", Toast.LENGTH_LONG).show();
        }
    }

    private Uri savePhotoToGalleryQ(byte[] jpeg) {
        String name = "L360_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Live360");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            Log.e(TAG, "saveQ: insert returned null");
            return null;
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                Log.e(TAG, "saveQ: openOutputStream returned null for " + uri);
                return null;
            }
            os.write(jpeg);
            Log.i(TAG, "saveQ: wrote " + jpeg.length + " bytes to " + uri);
            return uri;
        } catch (IOException e) {
            Log.e(TAG, "saveQ: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Uri savePhotoToGalleryLegacy(byte[] jpeg) {
        String name = "L360_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".jpg";
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Live360");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "saveLegacy: mkdirs failed for " + dir);
            return null;
        }
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(jpeg);
            android.media.MediaScannerConnection.scanFile(
                    this, new String[]{f.getAbsolutePath()},
                    new String[]{"image/jpeg"}, null);
            Log.i(TAG, "saveLegacy: wrote " + jpeg.length + " bytes to " + f);
            return Uri.fromFile(f);
        } catch (IOException e) {
            Log.e(TAG, "saveLegacy: " + e.getMessage());
            return null;
        }
    }

    // ============ Камера ============

    private void startCamera(SurfaceTexture st) {
        if (cameraStarted) return;
        cameraStarted = true;
        if (st == null) { overlay.setText("SurfaceTexture null"); return; }

        if (surface != null) surface.release();
        surface = new Surface(st);

        frameCount = 0;
        lastFpsFrameCount = 0;
        lastFpsTimeMs = System.currentTimeMillis();
        currentFps = 0f;

        // ★ Получаем выбранное разрешение превью
        final int videoRes = PhotoSettings.getVideoResolution(this);
        final int vW = PhotoSettings.getVideoWidth(videoRes);
        final int vH = PhotoSettings.getVideoHeight(videoRes);
        resolutionStr = vW + "×" + vH + " (запрос)";
        refreshStats();

        try {
            // Передаем запрошенное разрешение в декодер как подсказку
            decoder = new H264Decoder(surface, vW, vH);
            decoder.setOnVideoFormatListener((w, h) -> runOnUiThread(() -> {
                // Если камера вернет другое разрешение, показываем реальное
                resolutionStr = w + "×" + h;
                refreshStats();
                Log.i(TAG, "preview real size = " + w + "x" + h);
            }));
            decoder.start();
            decoderReady.set(true);
            Log.i(TAG, "decoder started (" + vW + "x" + vH + ")");
        } catch (Exception e) {
            Log.e(TAG, "decoder start: " + e.getMessage());
            overlay.setText("decoder: " + e.getMessage());
            return;
        }

        final int photoRes = PhotoSettings.getResolution(this);
        Log.i(TAG, "using photo_res=0x" + Integer.toHexString(photoRes)
                + " video_res=0x" + Integer.toHexString(videoRes));

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(android.hardware.usb.UsbDeviceConnection c,
                                    android.hardware.usb.UsbInterface i,
                                    android.hardware.usb.UsbEndpoint in,
                                    android.hardware.usb.UsbEndpoint out) {
                Log.i(TAG, "USB connected. Starting LiveViewSession");

                // ★ Передаем videoRes в сессию
                session = new LiveViewSession(c, in, out, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264Decoder d = decoder;
                                if (decoderReady.get() && d != null) {
                                    d.feed(h264, ptsUs);
                                    frameCount++;
                                }
                            }
                            @Override public void onError(String msg) {
                                Log.e(TAG, "LiveView error: " + msg);
                                runOnUiThread(() -> overlay.setText("Err: " + msg));
                            }
                        });
                final LiveViewSession sess = session;
                new Thread(() -> {
                    boolean ok = sess.start();
                    running = ok;
                    runOnUiThread(() -> overlay.setText(ok
                            ? "Live view started (" + PhotoSettings.label(photoRes) + ")"
                            : "Start failed"));
                    Log.i(TAG, "session.start() = " + ok);
                }, "LV-start").start();
            }
            @Override public void onDetached() {
                runOnUiThread(() -> overlay.setText("Detached"));
            }
            @Override public void onPermissionDenied() {
                Log.e(TAG, "USB permission denied");
                runOnUiThread(() -> {
                    overlay.setText("Нет разрешения USB");
                    Toast.makeText(ViewerActivity.this,
                            "Разрешение на USB-камеру не выдано", Toast.LENGTH_LONG).show();
                });
                cameraStarted = false;
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> overlay.setText("USB: " + msg));
            }
        });
    }

    @Override protected void onPause() {
        super.onPause();
        final LiveViewSession s = session; session = null; running = false;
        if (s != null) new Thread(s::stop, "LV-stop").start();

        final UsbCameraManager u = usb; usb = null;
        if (u != null) new Thread(u::stop, "USB-stop").start();

        final H264Decoder d = decoder; decoder = null; decoderReady.set(false);
        if (d != null) new Thread(d::stop, "dec-stop").start();

        if (surface != null) { surface.release(); surface = null; }
        cameraStarted = false;
        glSurface.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopFpsUpdater();
        if (session != null) session.stop();
        if (usb != null) usb.stop();
        if (decoder != null) decoder.stop();
        if (surface != null) { surface.release(); surface = null; }
    }
}