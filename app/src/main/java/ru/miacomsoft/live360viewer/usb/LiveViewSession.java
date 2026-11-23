package ru.miacomsoft.live360viewer.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class LiveViewSession {

    private static final String TAG = "LiveView";
    private static final int MAX_FRAME = 1 * 1024 * 1024;
    private static final long PHOTO_TIMEOUT_MS = 30_000L;

    public interface FrameListener {
        void onVideoFrame(byte[] h264, long ptsUs);
        void onError(String msg);
    }
    public interface PhotoListener {
        void onPhotoReady(byte[] jpeg, long timestampMs);
        void onPhotoError(String msg);
    }

    private final UsbBulkTransfer io;
    private final FrameListener listener;
    private final int photoRes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread reader;
    private Thread keepAlive;
    private int frameCounter = 0;

    private final Object pauseLock = new Object();
    private volatile boolean pauseRequested = false;

    public LiveViewSession(UsbDeviceConnection conn, UsbEndpoint epIn, UsbEndpoint epOut,
                           int photoRes, FrameListener l) {
        this.io = new UsbBulkTransfer(conn, epIn, epOut);
        this.photoRes = photoRes;
        this.listener = l;
    }

    public boolean start() {
        try {
            byte[] notifyCdb = new byte[]{0x7A, 0x00, 0x02, 0, 0, 0, 0, 0, 0x01, 0x01, 0, 0, 0x0C, 0x01, 0, 0};
            Log.i(TAG, "notify result=" + exec(0x7A, notifyCdb, 0, false, null));
            Thread.sleep(200);

            byte[] inqCdb = new byte[]{0x12, 0x00, 0x00, 0x00, 36, 0x00};
            Log.i(TAG, "INQUIRY result=" + exec(0x0012, inqCdb, 36, true, new byte[64]));

            byte[] initCdb = new byte[]{0x7A, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            int initRes = exec(0x4800, initCdb, 8, true, new byte[16]);
            Log.i(TAG, "init result=" + initRes);
            if (initRes < 0) return false;
            Thread.sleep(100);

            byte[] startCdb = new byte[]{0x7A, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            Log.i(TAG, "startComm result=" + exec(0x480A, startCdb, 0, false, null));
            Thread.sleep(200);

            sendSetAllSettings();

            byte[] clearCdb = new byte[]{0x7A, 0x01, (byte) 0x85, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            Log.i(TAG, "clearBuf result=" + exec(0x481B, clearCdb, 0, false, null));
            Thread.sleep(100);

            byte[] fwCdb = new byte[]{0x7A, 0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            Log.i(TAG, "getFwVersion result=" + exec(0x4830, fwCdb, 64, true, new byte[128]));

            byte[] liveCdb = new byte[]{
                    0x7A, 0x01, 0x01, 0, 0, 0, 0, 0, 0x00, 0x0A, 0x00, 0, 0, 0, 0, 0
            };
            Log.i(TAG, "startLive result=" + exec(0x4811, liveCdb, 0, false, null));
            Thread.sleep(300);

            running.set(true);
            reader = new Thread(this::readLoop, "LV-reader"); reader.start();
            keepAlive = new Thread(this::keepAliveLoop, "LV-keepalive"); keepAlive.start();
            Log.i(TAG, "Live view started (photo_res=0x"
                    + Integer.toHexString(photoRes) + " bitrate=8)");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sendSetAllSettings() {
        byte[] settingsCdb = new byte[]{
                0x7A, 0x00, 0x04,
                (byte) (photoRes & 0xFF),       // [3] photo_res (0x03/0x04)
                0x0A,                            // [4] video_res = 0x0A (1280x640) ★
                0x00,                            // [5] evb = 0 (auto)
                0x00,                            // [6] wb = 0
                0x00,                            // [7] filter = 0
                0x01,                            // [8] logo = 1
                0x08,                            // [9] bitrate = 8 (MID)
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        Log.i(TAG, "setSettings photo_res=0x" + Integer.toHexString(photoRes)
                + " video_res=10 bitrate=8(MID) result="
                + exec(0x4857, settingsCdb, 0, false, null));
    }

    public void stop() {
        if (!running.get()) return;
        running.set(false);
        if (reader != null) { try { reader.join(1500); } catch (InterruptedException ignored) {} reader = null; }
        if (keepAlive != null) { try { keepAlive.join(1000); } catch (InterruptedException ignored) {} keepAlive = null; }
        try {
            byte[] stopCdb = new byte[]{0x7A, 0x01, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            exec(0x4812, stopCdb, 0, false, null);
        } catch (Exception ignored) {}
    }

    private void keepAliveLoop() {
        byte[] cdb = new byte[]{0x7A, 0x03, (byte) 0xFF};
        while (running.get()) {
            waitIfPaused();
            try {
                exec(0x037A, cdb, 64, true, new byte[64]);
                Thread.sleep(ScsiConstants.KEEPALIVE_PERIOD_MS);
            } catch (InterruptedException e) { return; }
            catch (Exception e) { Log.w(TAG, "keepalive: " + e.getMessage()); }
        }
    }

    private void readLoop() {
        byte[] frameBuf = new byte[MAX_FRAME];
        byte[] frameCdb = new byte[]{0x7A, 0x05, 0x01, 0, 0, 0, 0, 0, 0x00, 0, 0, 0, 0, 0, 0, 0};
        byte[] response = new byte[ScsiConstants.DEFAULT_READ_BUF];

        while (running.get()) {
            waitIfPaused();
            try {
                int totalLen = 0;
                int keepReading = 0;
                int retries = 0;

                while (keepReading == 0 && retries < 5) {
                    int n = exec(0x4820, frameCdb, ScsiConstants.DEFAULT_READ_BUF, true, response);
                    if (n <= 0) { retries++; Thread.sleep(20); continue; }
                    if (n < 0x20) { retries++; continue; }
                    retries = 0;
                    int status = response[0] & 0xFF;
                    keepReading = response[1] & 0xFF;
                    // ★ GET_FRAME (0x4820) — offset 0x1C
                    int chunkLen = readIntLE(response, 0x1C);
                    if (status == 3 || status == 1) { Thread.sleep(10); continue; }
                    if (status != 0) { Log.w(TAG, "frame status=" + status); break; }
                    if (chunkLen <= 0 || chunkLen > n - 0x20) { break; }
                    if (totalLen + chunkLen > MAX_FRAME) { break; }
                    System.arraycopy(response, 0x20, frameBuf, totalLen, chunkLen);
                    totalLen += chunkLen;
                }

                if (totalLen > 0) parseFrame(frameBuf, totalLen);
                else Thread.sleep(5);
            } catch (InterruptedException e) { return; }
            catch (Exception e) {
                Log.e(TAG, "readLoop: " + e.getMessage());
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void parseFrame(byte[] buf, int n) {
        int start = findAnnexB(buf, 0, Math.min(n, 1024));
        if (start < 0) return;
        int nalOff = start + (buf[start + 2] == 0 ? 4 : 3);
        int nalType = (nalOff < n) ? (buf[nalOff] & 0x1F) : -1;
        if (frameCounter++ % 30 == 1) {
            Log.d(TAG, "frame n=" + n + " startCode@" + start + " nalType=" + nalType);
        }
        byte[] h264 = new byte[n - start];
        System.arraycopy(buf, start, h264, 0, n - start);
        if (listener != null) listener.onVideoFrame(h264, System.nanoTime() / 1000L);
    }

    // ============ ФОТО ============

    public void takePhoto(PhotoListener listener) {
        if (listener == null) return;
        if (!running.get()) { listener.onPhotoError("session not running"); return; }

        synchronized (pauseLock) {
            pauseRequested = true;
            pauseLock.notifyAll();
        }

        new Thread(() -> {
            boolean liveViewStopped = false;
            try {
                Thread.sleep(300);

                // 1) STOP_LIVE_VIEW
                byte[] stopCdb = new byte[]{0x7A, 0x01, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int rStop = exec(0x4812, stopCdb, 0, false, null);
                Log.i(TAG, "photo: stopLive=" + rStop);
                liveViewStopped = true;
                Thread.sleep(500);

                // 2) Повторно применяем настройки фото (после stopLive камера могла сбросить)
                Log.i(TAG, "photo: re-sending setSettings photo_res=0x"
                        + Integer.toHexString(photoRes) + " bitrate=8");
                sendSetAllSettings();
                Thread.sleep(200);

                // 3) CLEAR_BUFFER — чтобы не пришло старое фото из буфера
                byte[] clearCdb = new byte[]{0x7A, 0x01, (byte) 0x85, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                Log.i(TAG, "photo: clearBuf=" + exec(0x481B, clearCdb, 0, false, null));
                Thread.sleep(100);

                // 4) TAKE_PICTURE
                byte[] takeCdb = new byte[]{0x7A, 0x01, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int rTake = exec(0x4815, takeCdb, 0, false, null);
                Log.i(TAG, "photo: takePicture=" + rTake);
                if (rTake < 0) { listener.onPhotoError("TAKE_PICTURE failed: " + rTake); return; }

                // 5) Даём камере время снять и закодировать JPEG
                //    Увеличено до 2000 мс — 13 МП требует больше времени
                Thread.sleep(2000);

                // 6) GET_PICTURE — читаем чанки
                byte[] getCdb = new byte[]{0x7A, 0x05, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                byte[] response = new byte[ScsiConstants.DEFAULT_READ_BUF];
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                long deadline = System.currentTimeMillis() + PHOTO_TIMEOUT_MS;
                int emptyCount = 0;
                boolean headerLogged = false;
                int chunkIdx = 0;

                while (System.currentTimeMillis() < deadline) {
                    int n = exec(0x4821, getCdb, ScsiConstants.DEFAULT_READ_BUF, true, response);
                    if (n <= 0) {
                        Log.d(TAG, "photo: empty n=" + n + " retry=" + emptyCount);
                        Thread.sleep(150);
                        if (++emptyCount > 100) break;
                        continue;
                    }
                    if (n < 0x20) { Thread.sleep(150); continue; }
                    emptyCount = 0;
                    chunkIdx++;

                    if (!headerLogged) {
                        StringBuilder sb = new StringBuilder("photo header[0..0x24]: ");
                        for (int i = 0; i < Math.min(0x24, n); i++)
                            sb.append(String.format("%02X ", response[i]));
                        Log.i(TAG, sb.toString());
                        headerLogged = true;
                    }

                    int status      = response[0] & 0xFF;
                    int keepReading = response[1] & 0xFF;
                    // ★★★ ИСПРАВЛЕНИЕ: offset 0x10 (а не 0x1C) — как в оригинале ★★★
                    int chunkLen    = readIntLE(response, 0x10);

                    Log.i(TAG, "photo chunk#" + chunkIdx
                            + " status=" + status
                            + " keepReading=" + keepReading
                            + " chunkLen=" + chunkLen
                            + " n=" + n
                            + " total=" + out.size());

                    if (status == 1 || status == 3) { Thread.sleep(250); continue; }
                    if (status != 0) {
                        Log.w(TAG, "photo: unexpected status=" + status);
                        Thread.sleep(200);
                        if (++emptyCount > 20) break;
                        continue;
                    }
                    if (chunkLen <= 0) {
                        if (keepReading == 0) break;
                        Thread.sleep(150);
                        continue;
                    }

                    int available = n - 0x20;
                    if (chunkLen > available) {
                        Log.e(TAG, "photo: chunkLen=" + chunkLen
                                + " > available " + available + " — DATA TRUNCATED");
                        break;
                    }
                    out.write(response, 0x20, chunkLen);

                    // ★ Убрано условие out.size() > 1024
                    if (keepReading == 0 && out.size() > 0) {
                        Log.i(TAG, "photo: keepReading=0, total=" + out.size());
                        break;
                    }
                }

                // 7) Возвращаемся в live-view
                byte[] startCdb = new byte[]{0x7A, 0x01, 0x01, 0, 0, 0, 0, 0, 0x00, 0x0A, 0x00, 0, 0, 0, 0, 0};
                int rStart = exec(0x4811, startCdb, 0, false, null);
                Log.i(TAG, "photo: startLive=" + rStart);
                liveViewStopped = false;

                if (out.size() == 0) { listener.onPhotoError("no photo data"); return; }

                byte[] raw = out.toByteArray();
                int js = findJpegStart(raw);
                int je = findJpegEnd(raw, js);
                byte[] jpeg;
                if (js >= 0 && je > js) {
                    jpeg = new byte[je - js + 2];
                    System.arraycopy(raw, js, jpeg, 0, jpeg.length);
                    Log.i(TAG, "photo: JPEG " + jpeg.length + " bytes (raw " + raw.length + ")");
                } else {
                    jpeg = raw;
                    Log.w(TAG, "photo: no JPEG markers, raw " + raw.length + " bytes");
                }

                int kb = jpeg.length / 1024;
                Log.i(TAG, "photo: final size=" + kb + " KB (photo_res=0x"
                        + Integer.toHexString(photoRes) + ")");
                if (kb < 100) {
                    Log.w(TAG, "photo: VERY SMALL — проверьте photo_res и bitrate");
                } else if (kb < 300) {
                    Log.w(TAG, "photo: SMALL — возможно LOW bitrate");
                } else {
                    Log.i(TAG, "photo: OK");
                }
                listener.onPhotoReady(jpeg, System.currentTimeMillis());

            } catch (Exception e) {
                Log.e(TAG, "takePhoto: " + e);
                listener.onPhotoError(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (liveViewStopped) {
                    try {
                        byte[] startCdb = new byte[]{0x7A, 0x01, 0x01, 0, 0, 0, 0, 0, 0x00, 0x0A, 0x00, 0, 0, 0, 0, 0};
                        exec(0x4811, startCdb, 0, false, null);
                        Log.i(TAG, "photo: startLive (finally) OK");
                    } catch (Exception ignored) {}
                }
                synchronized (pauseLock) {
                    pauseRequested = false;
                    pauseLock.notifyAll();
                }
            }
        }, "LV-photo").start();
    }

    private void waitIfPaused() {
        synchronized (pauseLock) {
            while (pauseRequested && running.get()) {
                try { pauseLock.wait(200); }
                catch (InterruptedException e) { return; }
            }
        }
    }

    private static int findJpegStart(byte[] buf) {
        for (int i = 0; i < buf.length - 2; i++) {
            if ((buf[i] & 0xFF) == 0xFF && (buf[i+1] & 0xFF) == 0xD8
                    && (buf[i+2] & 0xFF) == 0xFF) return i;
        }
        return -1;
    }

    private static int findJpegEnd(byte[] buf, int from) {
        if (from < 0) from = 0;
        for (int i = buf.length - 2; i >= from; i--) {
            if ((buf[i] & 0xFF) == 0xFF && (buf[i+1] & 0xFF) == 0xD9) return i;
        }
        return -1;
    }

    private static int findAnnexB(byte[] buf, int from, int to) {
        int limit = Math.min(to, buf.length) - 3;
        for (int i = from; i < limit; i++) {
            if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 0 && buf[i+3] == 1) return i;
            if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 1) return i;
        }
        return -1;
    }

    private static int readIntLE(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off+1] & 0xFF) << 8)
                | ((buf[off+2] & 0xFF) << 16) | ((buf[off+3] & 0xFF) << 24);
    }

    private int exec(int code, byte[] cdb, int dataLen, boolean read, byte[] out) {
        ScsiPacket pkt = new ScsiPacket(code, dataLen, read);
        pkt.setCdb(cdb);
        return io.execute(pkt, null, out);
    }
}