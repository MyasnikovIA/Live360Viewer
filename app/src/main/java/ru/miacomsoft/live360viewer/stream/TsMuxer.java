package ru.miacomsoft.live360viewer.stream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TsMuxer {
    private static final int TS_PACKET_SIZE = 188;
    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x0100;
    private static final int PID_AUDIO = 0x0101;

    private final Object lock = new Object();
    private final List<byte[]> output = new ArrayList<>();

    private int ccPat = 0, ccPmt = 0, ccVideo = 0, ccAudio = 0;
    private long lastPsiMs = 0;
    private long lastPcrMs = 0;

    public void writeVideo(byte[] annexB, long ptsUs) {
        if (annexB == null || annexB.length == 0) return;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            ensurePsi(now);
            boolean withPcr = (now - lastPcrMs) >= 40;
            if (withPcr) lastPcrMs = now;
            writePes(PID_VIDEO, 0xE0, annexB, ptsUs, withPcr);
        }
    }

    public void writeAudio(byte[] adts, long ptsUs) {
        if (adts == null || adts.length == 0) return;
        synchronized (lock) {
            ensurePsi(System.currentTimeMillis());
            writePes(PID_AUDIO, 0xC0, adts, ptsUs, false);
        }
    }

    public List<byte[]> drain() {
        synchronized (lock) {
            if (output.isEmpty()) return new ArrayList<>();
            List<byte[]> copy = new ArrayList<>(output);
            output.clear();
            return copy;
        }
    }

    private void ensurePsi(long now) {
        if (now - lastPsiMs < 500) return;
        lastPsiMs = now;

        byte[] pat = {
                0x00, 0x00, (byte)0xB0, 0x0D, 0x00, 0x01, (byte)0xC1, 0x00, 0x00, 0x00, 0x01,
                (byte)0xE0, (byte)(PID_PMT >> 8), (byte)(PID_PMT & 0xFF), 0x00, 0x00, 0x00, 0x00
        };
        writePsiPacket(PID_PAT, pat, ccPat++ & 0x0F);

        byte[] pmt = {
                0x00, 0x02, (byte)0xB0, 0x17, 0x00, 0x01, (byte)0xC1, 0x00, 0x00,
                (byte)0xE1, 0x00, (byte)0xF0, 0x00,
                0x1B, (byte)0xE1, 0x00, (byte)0xF0, 0x00, // Video (H.264)
                0x0F, (byte)0xE1, 0x01, (byte)0xF0, 0x00, // Audio (AAC)
                0x00, 0x00, 0x00, 0x00
        };
        writePsiPacket(PID_PMT, pmt, ccPmt++ & 0x0F);
    }

    private void writePsiPacket(int pid, byte[] sec, int cc) {
        byte[] ts = new byte[TS_PACKET_SIZE];
        Arrays.fill(ts, (byte)0xFF);
        ts[0] = 0x47;
        ts[1] = (byte)(0x40 | ((pid >> 8) & 0x1F));
        ts[2] = (byte)(pid & 0xFF);
        ts[3] = (byte)(0x10 | (cc & 0x0F));
        System.arraycopy(sec, 0, ts, 4, Math.min(sec.length, 184));
        output.add(ts);
    }

    private void writePes(int pid, int streamId, byte[] payload, long ptsUs, boolean withPcr) {
        long pts90 = (ptsUs * 9L) / 100L;

        ByteArrayOutputStream pes = new ByteArrayOutputStream();
        pes.write(0x00); pes.write(0x00); pes.write(0x01);
        pes.write(streamId);

        if (pid == PID_AUDIO) {
            // Длина PES для аудио (8 байт заголовка + длина данных)
            int pesLen = 8 + payload.length;
            if (pesLen > 0xFFFF) pesLen = 0;
            pes.write((pesLen >> 8) & 0xFF);
            pes.write(pesLen & 0xFF);
        } else {
            // Для видео длина 0 (без ограничений)
            pes.write(0x00); pes.write(0x00);
        }

        pes.write(0x84); // Flags (Data alignment)
        pes.write(0x80); // PTS only
        pes.write(0x05); // PTS length

        pes.write((int)(0x21 | (((pts90 >> 30) & 0x07) << 1)));
        pes.write((int)((pts90 >> 22) & 0xFF));
        pes.write((int)(0x01 | (((pts90 >> 15) & 0x7F) << 1)));
        pes.write((int)((pts90 >> 7) & 0xFF));
        pes.write((int)(0x01 | ((pts90 & 0x7F) << 1)));

        pes.write(payload, 0, payload.length);
        byte[] pesBytes = pes.toByteArray();

        int offset = 0;
        boolean first = true;
        while (offset < pesBytes.length) {
            int remaining = pesBytes.length - offset;
            byte[] ts = new byte[TS_PACKET_SIZE];
            Arrays.fill(ts, (byte)0xFF);

            ts[0] = 0x47;
            ts[1] = (byte)(((first ? 1 : 0) << 6) | ((pid >> 8) & 0x1F));
            ts[2] = (byte)(pid & 0xFF);

            int cc = (pid == PID_VIDEO) ? (ccVideo++ & 0x0F) : (ccAudio++ & 0x0F);

            int payloadLen = 184;
            int adaptationLen = -1;

            // Вставляем PCR в первый пакет видео-кадра
            if (first && withPcr) {
                adaptationLen = 7; // flags(1) + PCR(6)
            } else if (remaining < 184) {
                adaptationLen = 184 - remaining - 1;
            }

            if (adaptationLen >= 0) {
                ts[3] = (byte)(0x30 | cc); // Adaptation + Payload
                ts[4] = (byte)adaptationLen;
                int p = 5;
                if (adaptationLen > 0) {
                    ts[p++] = (byte)((withPcr && first) ? 0x10 : 0x00);
                    if (withPcr && first) {
                        long pcrBase = pts90 & 0x1FFFFFFFFL;
                        ts[p++] = (byte)(pcrBase >> 25);
                        ts[p++] = (byte)(pcrBase >> 17);
                        ts[p++] = (byte)(pcrBase >> 9);
                        ts[p++] = (byte)(pcrBase >> 1);
                        ts[p++] = (byte)(((pcrBase & 0x01) << 7) | 0x7E);
                        ts[p++] = 0x00;
                    }
                    while (p < 5 + adaptationLen) ts[p++] = (byte)0xFF;
                }
                payloadLen = 184 - (1 + adaptationLen);
            } else {
                ts[3] = (byte)(0x10 | cc); // Payload only
            }

            int payloadStart = 4 + (adaptationLen >= 0 ? (1 + adaptationLen) : 0);
            int copy = Math.min(payloadLen, remaining);
            System.arraycopy(pesBytes, offset, ts, payloadStart, copy);

            output.add(ts);
            offset += copy;
            first = false;
        }
    }
}