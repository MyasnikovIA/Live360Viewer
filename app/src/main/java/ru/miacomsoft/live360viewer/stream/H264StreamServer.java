package ru.miacomsoft.live360viewer.stream;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

public class H264StreamServer {
    private static final String TAG = "H264StreamServer";

    public interface Listener {
        void onListening(String ipAddress, int port);
        void onClientConnected(String remoteAddress);
        void onClientDisconnected();
        void onError(String message);
    }

    private final int port;
    private final boolean audio;
    private final boolean isHttp; // Новый флаг

    private Listener listener;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream clientOut;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean hasClient = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final Object clientLock = new Object();

    private Thread acceptThread;
    private volatile byte[] cachedSps;
    private volatile byte[] cachedPps;
    private volatile byte[] cachedIdr;

    public H264StreamServer(int port) {
        this(port, false, false);
    }

    public H264StreamServer(int port, boolean audio) {
        this(port, audio, false);
    }

    public H264StreamServer(int port, boolean audio, boolean isHttp) {
        this.port = port;
        this.audio = audio;
        this.isHttp = isHttp;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        acceptThread = new Thread(this::acceptLoop, "stream-accept" + (audio ? "-a" : "-v"));
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            Log.i(TAG, (audio ? "[audio] " : "[video] ") + "listening on :" + port);
            if (listener != null) listener.onListening(getLocalIpv4(), port);

            while (running.get()) {
                Socket s = serverSocket.accept();

                if (hasClient.get()) {
                    try { s.close(); } catch (IOException ignored) {}
                    continue;
                }

                try {
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);

                    // Читаем HTTP-запрос от VLC
                    if (isHttp) {
                        s.setSoTimeout(1000);
                        InputStream is = s.getInputStream();
                        byte[] buf = new byte[1024];
                        try { is.read(buf); } catch (Exception ignored) {}
                        s.setSoTimeout(0);
                    }

                    synchronized (clientLock) {
                        clientSocket = s;
                        clientOut = s.getOutputStream();
                        if (isHttp) {
                            String headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: video/mp2t\r\n" +
                                    "Connection: close\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n\r\n";
                            clientOut.write(headers.getBytes("US-ASCII"));
                            clientOut.flush();
                        }
                    }

                    hasClient.set(true);
                    if (listener != null) {
                        listener.onClientConnected(String.valueOf(s.getRemoteSocketAddress()));
                    }
                    if (!audio && !isHttp) sendCachedBootstrap(); // Для HTTP не шлем bootstrap, VLC сам разберется в TS
                } catch (IOException e) {
                    disconnectClient();
                }
            }
        } catch (IOException e) {
            if (listener != null) listener.onError("bind: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void sendCachedBootstrap() {
        // ...
    }

    public void sendFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return;

        if (!audio && !isHttp) {
            // Парсинг NAL нужен только для сырого H.264, для TS он не нужен
            List<byte[]> nals = splitAnnexB(frame);
            for (byte[] nal : nals) {
                if (nal.length == 0) continue;
                int type = nal[0] & 0x1F;
                switch (type) {
                    case 7: cachedSps = nal; break;
                    case 8: cachedPps = nal; break;
                    case 5: cachedIdr = buildIdrWithParams(nal); break;
                }
            }
        }

        if (!hasClient.get()) return;

        synchronized (writeLock) {
            OutputStream out = clientOut;
            if (out == null) return;
            try {
                out.write(frame);
                out.flush();
            } catch (IOException e) {
                disconnectClient();
            }
        }
    }

    private byte[] buildIdrWithParams(byte[] idr) {
        byte[] sps = cachedSps;
        byte[] pps = cachedPps;
        if (sps == null || pps == null) return idr;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(sps.length + pps.length + idr.length);
        try { bos.write(sps); bos.write(pps); bos.write(idr); } catch (IOException ignored) {}
        return bos.toByteArray();
    }

    private static List<byte[]> splitAnnexB(byte[] buf) {
        // ... (оставьте код splitAnnexB без изменений из вашего проекта)
        return new ArrayList<>();
    }

    public void disconnectClient() {
        if (!hasClient.getAndSet(false)) return;
        synchronized (clientLock) {
            try { if (clientOut != null) clientOut.close(); } catch (Exception ignored) {}
            try { if (clientSocket != null) clientSocket.close(); } catch (Exception ignored) {}
            clientOut = null;
            clientSocket = null;
        }
        if (listener != null) listener.onClientDisconnected();
    }

    public void stop() {
        running.set(false);
        disconnectClient();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }

    public boolean hasClient() { return hasClient.get(); }

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