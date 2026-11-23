package ru.miacomsoft.live360viewer.stream;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP-сервер, транслирующий «сырой» H.264 (Annex-B) подключённому клиенту.
 *
 * Особенности для совместимости с VLC/ffplay:
 *   - Кэширует последние SPS/PPS и последний IDR-кадр.
 *   - При подключении клиента немедленно отправляет SPS+PPS+IDR,
 *     затем — живой поток. Это позволяет VLC подключаться в любой момент
 *     и не ждать следующего keyframe.
 *
 * Открытие в VLC:    vlc tcp/h264://IP:PORT
 * Открытие в ffplay: ffplay -fflags nobuffer tcp://IP:PORT
 */
public class H264StreamServer {

    private static final String TAG = "H264StreamServer";

    public interface Listener {
        void onListening(String ipAddress, int port);
        void onClientConnected(String remoteAddress);
        void onClientDisconnected();
        void onError(String message);
    }

    private final int port;
    private Listener listener;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream clientOut;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean hasClient = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final Object clientLock = new Object();

    private Thread acceptThread;

    // Кэш параметрических NAL-юнитов и последнего IDR
    private volatile byte[] cachedSps;
    private volatile byte[] cachedPps;
    private volatile byte[] cachedIdr;

    private volatile long framesSent = 0;
    private volatile long bytesSent = 0;

    public H264StreamServer(int port) {
        this.port = port;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        acceptThread = new Thread(this::acceptLoop, "stream-accept");
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            Log.i(TAG, "listening on :" + port);
            if (listener != null) listener.onListening(getLocalIpv4(), port);

            while (running.get()) {
                Socket s;
                try {
                    s = serverSocket.accept();
                } catch (IOException e) {
                    if (running.get()) Log.w(TAG, "accept: " + e.getMessage());
                    break;
                }

                if (hasClient.get()) {
                    Log.w(TAG, "rejecting " + s.getRemoteSocketAddress() + " (busy)");
                    try { s.close(); } catch (IOException ignored) {}
                    continue;
                }

                try {
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    synchronized (clientLock) {
                        clientSocket = s;
                        clientOut = s.getOutputStream();
                    }
                    framesSent = 0;
                    bytesSent = 0;
                    hasClient.set(true);
                    Log.i(TAG, "client connected: " + s.getRemoteSocketAddress());
                    if (listener != null) {
                        listener.onClientConnected(String.valueOf(s.getRemoteSocketAddress()));
                    }
                    // ★ Немедленно отдаём кэш (SPS+PPS+IDR), чтобы VLC
                    //   начал декодировать, не дожидаясь свежего keyframe.
                    sendCachedBootstrap();
                } catch (IOException e) {
                    Log.e(TAG, "client setup: " + e.getMessage());
                    disconnectClient();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "bind failed: " + e.getMessage());
            if (listener != null) listener.onError("bind: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void sendCachedBootstrap() {
        byte[] sps = cachedSps;
        byte[] pps = cachedPps;
        byte[] idr = cachedIdr;
        if (sps == null || pps == null) {
            Log.i(TAG, "bootstrap: no cached SPS/PPS yet — waiting for live frames");
            return;
        }
        synchronized (writeLock) {
            OutputStream out = clientOut;
            if (out == null) return;
            try {
                out.write(sps);
                out.write(pps);
                if (idr != null) out.write(idr);
                out.flush();
                int total = sps.length + pps.length + (idr != null ? idr.length : 0);
                Log.i(TAG, "bootstrap sent: SPS=" + sps.length
                        + " PPS=" + pps.length
                        + " IDR=" + (idr != null ? idr.length : 0));
                bytesSent += total;
            } catch (IOException e) {
                Log.w(TAG, "bootstrap write failed: " + e.getMessage());
                disconnectClient();
            }
        }
    }

    /**
     * Принимает один кадр (Annex-B). Кэширует SPS/PPS/IDR. Пересылает клиенту.
     */
    public void sendFrame(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return;

        // Разбираем NAL-юниты, обновляем кэш
        List<byte[]> nals = splitAnnexB(annexB);
        for (byte[] nal : nals) {
            if (nal.length == 0) continue;
            int type = nal[0] & 0x1F;
            switch (type) {
                case 7: cachedSps = nal; break;
                case 8: cachedPps = nal; break;
                case 5:
                    // Если IDR пришёл без SPS/PPS в этом же кадре —
                    // попробуем «склеить» с кэшем для будущих клиентов
                    cachedIdr = buildIdrWithParams(nal);
                    break;
                default: break;
            }
        }

        if (!hasClient.get()) return;

        synchronized (writeLock) {
            OutputStream out = clientOut;
            if (out == null) return;
            try {
                out.write(annexB);
                out.flush();
                framesSent++;
                bytesSent += annexB.length;
            } catch (IOException e) {
                Log.w(TAG, "write failed: " + e.getMessage());
                disconnectClient();
            }
        }
    }

    /** Приклеивает кэш SPS/PPS перед IDR, чтобы новый клиент получил валидный keyframe. */
    private byte[] buildIdrWithParams(byte[] idr) {
        byte[] sps = cachedSps;
        byte[] pps = cachedPps;
        if (sps == null || pps == null) return idr;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                sps.length + pps.length + idr.length);
        try {
            bos.write(sps);
            bos.write(pps);
            bos.write(idr);
        } catch (IOException ignored) {}
        return bos.toByteArray();
    }

    /** Разбивает Annex-B буфер на отдельные NAL-юниты (с start code). */
    private static List<byte[]> splitAnnexB(byte[] buf) {
        List<byte[]> result = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i < buf.length - 2) {
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                if (start >= 0) {
                    int end = i;
                    // отрезать trailing zero предыдущего NAL
                    while (end > start && buf[end - 1] == 0) end--;
                    byte[] nal = new byte[end - start];
                    System.arraycopy(buf, start, nal, 0, nal.length);
                    result.add(nal);
                }
                start = i;
                i += 3;
            } else if (buf[i] == 0 && buf[i + 1] == 0
                    && buf[i + 2] == 0 && i + 3 < buf.length
                    && buf[i + 3] == 1) {
                if (start >= 0) {
                    int end = i;
                    while (end > start && buf[end - 1] == 0) end--;
                    byte[] nal = new byte[end - start];
                    System.arraycopy(buf, start, nal, 0, nal.length);
                    result.add(nal);
                }
                start = i;
                i += 4;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < buf.length) {
            int end = buf.length;
            while (end > start && buf[end - 1] == 0) end--;
            byte[] nal = new byte[end - start];
            System.arraycopy(buf, start, nal, 0, nal.length);
            result.add(nal);
        }
        return result;
    }

    public void disconnectClient() {
        if (!hasClient.getAndSet(false)) return;
        synchronized (clientLock) {
            try { if (clientOut != null) clientOut.close(); } catch (Exception ignored) {}
            try { if (clientSocket != null) clientSocket.close(); } catch (Exception ignored) {}
            clientOut = null;
            clientSocket = null;
        }
        Log.i(TAG, "client disconnected");
        if (listener != null) listener.onClientDisconnected();
    }

    public void stop() {
        running.set(false);
        disconnectClient();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        if (acceptThread != null) {
            try { acceptThread.join(2000); } catch (InterruptedException ignored) {}
            acceptThread = null;
        }
    }

    public boolean hasClient() { return hasClient.get(); }
    public long getFramesSent() { return framesSent; }
    public long getBytesSent() { return bytesSent; }

    public static String getLocalIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}