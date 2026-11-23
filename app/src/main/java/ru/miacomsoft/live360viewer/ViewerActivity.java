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
import android.media.MediaScannerConnection;
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
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /**
     * ★ Очередь отложенных фото (только для Android < 10, когда нужно
     *   разрешение WRITE_EXTERNAL_STORAGE). Очередь, а не одно поле —
     *   чтобы не потерять фото при быстрых нажатиях до ответа на диалог.
     */
    private final ConcurrentLinkedQueue<byte[]> pendingPhotoQueue = new ConcurrentLinkedQueue<>();

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
                Log.i(TAG, "photo: onPhotoReady size="
                        + (jpeg == null ? "null" : jpeg.length));

                // ★ Проверяем, что JPEG не пустой и начинается с SOI (FF D8).
                //   Без этой проверки невалидные данные молча уходили в
                //   savePhotoQ/savePhotoLegacy, и пользователь видел
                //   "Saved", но файл в галерее отсутствовал.
                if (jpeg == null || jpeg.length < 4
                        || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                    final int len = (jpeg == null ? 0 : jpeg.length);
                    runOnUiThread(() -> {
                        btnPhoto.setEnabled(true);
                        overlay.setText("Photo err: invalid JPEG (" + len + " bytes)");
                        Toast.makeText(ViewerActivity.this,
                                "Камера вернула невалидный JPEG", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
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
                    Toast.makeText(ViewerActivity.this,
                            "Ошибка фото: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void savePhotoWithPermission(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            overlay.setText("Save failed: empty JPEG");
            Toast.makeText(this, "Пустой JPEG — сохранять нечего",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — MediaStore, разрешения не нужны
            savePhotoQ(jpeg);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // ★ Очередь, а не одно поле — не теряем фото при быстрых нажатиях
            pendingPhotoQueue.offer(jpeg);
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE);
            return;
        }
        savePhotoLegacy(jpeg);
    }

    /**
     * Android 10+ (Q). Единственный путь, который на 100% работает без
     * WRITE_EXTERNAL_STORAGE и сразу виден в галерее:
     *
     *   1) insert() с IS_PENDING=1 — файл создаётся, но скрыт от других приложений;
     *   2) openOutputStream() → write → flush → close;
     *   3) update(IS_PENDING=0) — файл «публикуется», медиа-сканер видит его
     *      автоматически, галерея/Photos подхватывают без перезагрузки.
     *
     * Если на шаге 2/3 что-то падает — удаляем «висящую» запись через
     * delete(uri, null, null), чтобы не оставлять мусор в MediaStore.
     */
    private void savePhotoQ(byte[] jpeg) {
        String name = "LIVE360_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + PHOTO_SUBDIR);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);   // ★ ключевой момент

        Uri uri = null;
        try {
            uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new IOException("insert() returned null");
            }

            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("openOutputStream() returned null");
                os.write(jpeg);
                os.flush();
            }

            // Публикуем — теперь файл виден всем
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, publish, null, null);

            Log.i(TAG, "savePhotoQ: saved " + jpeg.length + " bytes → " + uri);
            overlay.setText("Saved: " + name);
            Toast.makeText(this, "Фото сохранено: " + name, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "savePhotoQ failed: " + e.getMessage(), e);
            // Удаляем «висящую» PENDING-запись, иначе MediaStore засорится
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); }
                catch (Exception ignored) {}
            }
            overlay.setText("Save failed: " + e.getMessage());
            Toast.makeText(this, "Не удалось сохранить фото: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Android 9 и ниже. После записи в файл обязательно вызываем
     * MediaScannerConnection.scanFile(), иначе галерея не увидит файл
     * до перезагрузки/подключения к ПК — именно этого не хватало
     * в исходной реализации.
     */
    private void savePhotoLegacy(byte[] jpeg) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), PHOTO_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "savePhotoLegacy: mkdirs failed: " + dir);
            overlay.setText("Save failed: cannot create " + dir);
            Toast.makeText(this, "Не удалось создать папку " + dir,
                    Toast.LENGTH_LONG).show();
            return;
        }

        String name = "LIVE360_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        final File f = new File(dir, name);

        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(jpeg);
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "savePhotoLegacy write failed: " + e.getMessage(), e);
            overlay.setText("Save failed: " + e.getMessage());
            Toast.makeText(this, "Не удалось сохранить: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        // ★ Ключевой момент: без scanFile галерея файл не увидит
        MediaScannerConnection.scanFile(
                this,
                new String[]{f.getAbsolutePath()},
                new String[]{"image/jpeg"},
                (path, uri) -> Log.i(TAG, "scanFile: " + path + " → " + uri));

        Log.i(TAG, "savePhotoLegacy: saved " + jpeg.length + " bytes → " + f);
        overlay.setText("Saved: " + f.getAbsolutePath());
        Toast.makeText(this, "Фото: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_WRITE_STORAGE) return;

        boolean granted = res.length > 0
                && res[0] == PackageManager.PERMISSION_GRANTED;

        if (!granted) {
            int dropped = pendingPhotoQueue.size();
            pendingPhotoQueue.clear();
            Toast.makeText(this,
                    "Без разрешения фото не сохранить"
                            + (dropped > 0 ? " (" + dropped + " потеряно)" : ""),
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Сохраняем все накопившиеся фото
        byte[] j;
        int saved = 0;
        while ((j = pendingPhotoQueue.poll()) != null) {
            savePhotoLegacy(j);
            saved++;
        }
        if (saved > 1) {
            Toast.makeText(this, "Сохранено фото: " + saved, Toast.LENGTH_SHORT).show();
        }
    }
}