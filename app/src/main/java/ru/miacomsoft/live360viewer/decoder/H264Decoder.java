package ru.miacomsoft.live360viewer.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class H264Decoder {

    private static final String TAG = "H264Decoder";
    private static final String MIME = "video/avc";

    /** Слушатель реального размера видео, который вернул кодек (из SPS). */
    public interface OnVideoFormatListener {
        void onVideoFormat(int width, int height);
    }

    private MediaCodec codec;
    private final Surface surface;
    private final int width, height;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile boolean configured = false;
    private volatile boolean running = false;
    private Thread outputThread;

    // ★ Слушатель реального размера — чтобы UI показал «Превью: WxH»
    private volatile OnVideoFormatListener formatListener;

    public H264Decoder(Surface surface, int width, int height) {
        this.surface = surface;
        this.width = width;
        this.height = height;
    }

    public void setOnVideoFormatListener(OnVideoFormatListener l) {
        this.formatListener = l;
    }

    public synchronized void start() throws IOException {
        // Не задаём width/height жёстко — MediaCodec возьмёт SPS из потока.
        MediaFormat fmt = MediaFormat.createVideoFormat(MIME,
                width > 0 ? width : 1920,
                height > 0 ? height : 960);
        if (Build.VERSION.SDK_INT >= 21) {
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        }
        fmt.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1);

        codec = MediaCodec.createDecoderByType(MIME);
        codec.configure(fmt, surface, null, 0);
        codec.start();
        configured = true;
        running = true;
        outputThread = new Thread(this::drainLoop, "H264-drain");
        outputThread.start();
    }

    public synchronized void feed(byte[] annexB, long ptsUs) {
        if (!configured || codec == null) return;
        try {
            int inIdx = codec.dequeueInputBuffer(0);
            if (inIdx < 0) return;
            ByteBuffer inBuf = codec.getInputBuffer(inIdx);
            if (inBuf == null) return;
            inBuf.clear();
            int n = Math.min(inBuf.capacity(), annexB.length);
            inBuf.put(annexB, 0, n);
            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0);
        } catch (Exception e) {
            if (configured) Log.w(TAG, "feed: " + e.getMessage());
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    Log.i(TAG, "output format changed: " + outFmt);
                    int w = outFmt.getInteger(MediaFormat.KEY_WIDTH);
                    int h = outFmt.getInteger(MediaFormat.KEY_HEIGHT);
                    Log.i(TAG, "decoder real size = " + w + "x" + h);
                    // ★ Отдаём реальный размер наверх, в UI
                    OnVideoFormatListener l = formatListener;
                    if (l != null) l.onVideoFormat(w, h);
                } else if (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "drain: " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        configured = false;
        running = false;
        if (outputThread != null) {
            try { outputThread.join(1000); } catch (InterruptedException ignored) {}
            outputThread = null;
        }
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {}
        codec = null;
    }
}