package ru.miacomsoft.live360viewer.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioStreamSource {
    private static final String TAG = "AudioStreamSource";

    public interface Listener {
        void onAudioFrame(byte[] adtsFrame, long ptsUs);
        void onError(String msg);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNEL_COUNT = 1;

    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int AAC_BITRATE = 64000;
    private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;

    private static final int ADTS_HEADER_SIZE = 7;

    private final Context ctx;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    private AudioRecord audioRecord;
    private MediaCodec encoder;

    public AudioStreamSource(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    public static boolean hasPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean start() {
        if (!hasPermission(ctx)) {
            if (listener != null) listener.onError("RECORD_AUDIO not granted");
            return false;
        }
        if (!running.compareAndSet(false, true)) return true;

        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBuf <= 0) minBuf = 4096;
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuf * 4);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            audioRecord.startRecording();

            MediaFormat fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNEL_COUNT);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            running.set(false);
            return false;
        }

        thread = new Thread(this::loop, "AudioStreamSource");
        thread.start();
        return true;
    }

    public void stop() {
        running.set(false);
        Thread t = thread; thread = null;
        if (t != null) { try { t.join(500); } catch (InterruptedException ignored) {} }
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
    }

    private void loop() {
        final int pcmBufBytes = 2048;
        final byte[] pcm = new byte[pcmBufBytes];
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long ptsUs = 0;

        while (running.get()) {
            int n = audioRecord.read(pcm, 0, pcm.length);
            if (n <= 0) { try { Thread.sleep(10); } catch (Exception e) {} continue; }

            int inIdx = encoder.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
                ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                if (inBuf != null) {
                    inBuf.clear();
                    inBuf.put(pcm, 0, n);
                    encoder.queueInputBuffer(inIdx, 0, n, ptsUs, 0);
                    ptsUs += (n * 1000000L) / (SAMPLE_RATE * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8));
                }
            }

            while (true) {
                int outIdx = encoder.dequeueOutputBuffer(info, 0);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (outIdx < 0) continue;

                ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] raw = new byte[info.size];
                        outBuf.get(raw);
                        byte[] adts = wrapAdts(raw);
                        if (listener != null) listener.onAudioFrame(adts, info.presentationTimeUs);
                    }
                }
                encoder.releaseOutputBuffer(outIdx, false);
            }
        }
    }

    private static byte[] wrapAdts(byte[] raw) {
        int frameLen = raw.length + ADTS_HEADER_SIZE;
        byte[] adts = new byte[frameLen];

        adts[0] = (byte) 0xFF;
        adts[1] = (byte) 0xF1;

        int profile = 1; // LC
        int freqIndex = 4; // 44100
        int channelConfig = 1; // Mono

        adts[2] = (byte) ((profile << 6) | (freqIndex << 2) | ((channelConfig >> 2) & 0x01));
        adts[3] = (byte) (((channelConfig & 0x03) << 6) | ((frameLen >> 11) & 0x03));
        adts[4] = (byte) ((frameLen >> 3) & 0xFF);
        adts[5] = (byte) (((frameLen & 0x07) << 5) | 0x1F);
        adts[6] = (byte) 0xFC;

        System.arraycopy(raw, 0, adts, ADTS_HEADER_SIZE, raw.length);
        return adts;
    }
}