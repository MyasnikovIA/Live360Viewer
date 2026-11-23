package ru.miacomsoft.live360viewer.stream;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP-сервер, транслирующий «сырой» H.264 (Annex-B) подключённому клиенту.
 *
 * Формат на проводе — чистый Annex-B поток без упаковки/фрейминга.
 * Клиент парсит NAL-юниты сам. ffplay/ffmpeg/VLC понимают его «из коробки»:
 *
 *   ffplay tcp://192.168.1.10:8888
 *
 * или можно сохранить в файл и потом воспроизвести:
 *
 *   nc 192.168.1.10 8888 > stream.h264
 *   ffplay stream.h264
 *
 * Одновременно обслуживается не более одного клиента.
 * Новые подключения, пока есть активный клиент, отклоняются.
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

    // Статистика (для UI / отладки)
    private volatile long framesSent = 0;
    private volatile long bytesSent = 0;

    public H264StreamServer(int port) {
        this.port = port;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

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
            if (listener != null) {
                listener.onListening(getLocalIpv4(), port);
            }

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

    /**
     * Отправляет один кадр (Annex-B) подключённому клиенту.
     * Вызывается из USB-потока; если клиента нет — сразу возвращается.
     */
    public void sendFrame(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return;
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

    /** IPv4-адрес устройства в локальной сети — чтобы клиенту было куда подключаться. */
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