package ru.miacomsoft.live360viewer.stream;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

public class H264StreamClient {

    private static final String TAG = "H264StreamClient";
    // Увеличим таймаут до 7 секунд, чтобы дать сети больше времени
    private static final int CONNECT_TIMEOUT_MS = 7000;

    public interface Listener {
        void onConnecting(String host, int port);
        void onConnected(String remoteAddress);
        void onDisconnected(String reason);
        void onError(String message);
    }

    private final String host;
    private final int port;
    private Listener listener;

    private Socket socket;
    private OutputStream out;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private Thread connectThread;

    private volatile long framesSent = 0;
    private volatile long bytesSent = 0;

    public H264StreamClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        connectThread = new Thread(this::connectLoop, "stream-client");
        connectThread.start();
    }

    private void connectLoop() {
        long startTime = System.currentTimeMillis();
        try {
            Socket s = new Socket();
            if (listener != null) listener.onConnecting(host, port);
            Log.i(TAG, "Начинаем подключение к " + host + ":" + port);

            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);

            synchronized (writeLock) {
                socket = s;
                out = s.getOutputStream();
            }
            framesSent = 0;
            bytesSent = 0;
            connected.set(true);
            long timeTaken = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Успешно подключено за " + timeTaken + " мс");
            if (listener != null) listener.onConnected(host + ":" + port);

        } catch (SocketTimeoutException e) {
            String msg = "Таймаут (" + (System.currentTimeMillis() - startTime) + " мс). Пакеты блокируются (Фаервол/Антивирус/Роутер).";
            Log.e(TAG, msg);
            handleError(msg);
        } catch (ConnectException e) {
            String msg = "Соединение отклонено. Сервер не запущен или порт закрыт.";
            Log.e(TAG, msg);
            handleError(msg);
        } catch (UnknownHostException e) {
            String msg = "Неизвестный хост. Проверьте правильность ввода IP-адреса.";
            Log.e(TAG, msg);
            handleError(msg);
        } catch (IOException e) {
            String msg = "Ошибка ввода-вывода: " + e.getMessage();
            Log.e(TAG, msg, e);
            handleError(msg);
        }
    }

    private void handleError(String message) {
        connected.set(false);
        running.set(false);
        if (listener != null) listener.onError(message);
    }

    public void sendFrame(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return;
        if (!connected.get()) return;
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) return;
            try {
                o.write(annexB);
                o.flush();
                framesSent++;
                bytesSent += annexB.length;
            } catch (IOException e) {
                Log.w(TAG, "Ошибка отправки: " + e.getMessage());
                disconnect("write: " + e.getMessage());
            }
        }
    }

    private void disconnect(String reason) {
        if (!connected.getAndSet(false)) return;
        synchronized (writeLock) {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            out = null;
            socket = null;
        }
        Log.i(TAG, "Отключено: " + reason);
        if (listener != null) listener.onDisconnected(reason);
    }

    public void stop() {
        running.set(false);
        disconnect("stop requested");
        if (connectThread != null) {
            try { connectThread.join(2000); } catch (InterruptedException ignored) {}
            connectThread = null;
        }
    }

    public boolean isConnected() { return connected.get(); }
    public long getFramesSent() { return framesSent; }
    public long getBytesSent() { return bytesSent; }
}