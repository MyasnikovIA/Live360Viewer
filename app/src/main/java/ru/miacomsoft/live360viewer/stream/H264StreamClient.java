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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP-клиент для отправки «сырого» H.264 (Annex-B) на удалённый сервер.
 *
 * === Автопереподключение с экспоненциальным backoff ===
 *
 * start() запускает фоновый runLoop, который:
 *   1) пытается подключиться;
 *   2) после успеха блокируется в waitForDisconnect() до write error или stop();
 *   3) на disconnect делает retry с задержкой initial * mult^attempt ± 20%,
 *      ограниченной maxBackoffMs;
 *   4) после успешного connect счётчик попыток сбрасывается в 0 — длительная
 *      работа и последующий обрыв снова начинаются с малого backoff.
 *
 * maxReconnectAttempts:
 *   0 (default) → бесконечно;
 *   N > 0       → максимум N ПЕРЕподключений (не считая первоначального).
 *                 После N-й неудачи вызывается onReconnectGaveUp, runLoop
 *                 завершается, running=false.
 *
 * autoReconnect=false → после первого же сбоя клиент останавливается,
 *                       как в старой реализации.
 *
 * Обратная совместимость: старые Listener (только 4 abstract-метода) работают
 * без изменений — onReconnecting/onReconnectGaveUp имеют default-реализации.
 */
public class H264StreamClient {

    private static final String TAG = "H264StreamClient";
    private static final int CONNECT_TIMEOUT_MS = 7000;

    private static final long DEFAULT_INITIAL_BACKOFF_MS = 1000L;
    private static final long DEFAULT_MAX_BACKOFF_MS = 30000L;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    private static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 0; // 0 = infinite

    public interface Listener {
        void onConnecting(String host, int port);
        void onConnected(String remoteAddress);
        void onDisconnected(String reason);
        void onError(String message);

        /**
         * Вызывается ПЕРЕД каждой повторной попыткой (attempt >= 1).
         * attempt = номер переподключения (1-based), delayMs = сколько ждём
         * перед этой попыткой. Default делегирует в onConnecting для
         * обратной совместимости.
         */
        default void onReconnecting(String host, int port, int attempt, long delayMs) {
            onConnecting(host, port);
        }

        /**
         * Клиент сдался: достигнут maxReconnectAttempts или autoReconnect=false.
         * Default — no-op для обратной совместимости.
         */
        default void onReconnectGaveUp(String lastError) { }
    }

    private final String host;
    private final int port;
    private Listener listener;

    private Socket socket;
    private OutputStream out;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final Object stateLock = new Object();

    private Thread connectThread;

    private volatile long framesSent = 0;
    private volatile long bytesSent = 0;

    // Reconnect policy (настраивается до start())
    private volatile boolean autoReconnect = true;
    private volatile long initialBackoffMs = DEFAULT_INITIAL_BACKOFF_MS;
    private volatile long maxBackoffMs = DEFAULT_MAX_BACKOFF_MS;
    private volatile double backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
    private volatile int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;

    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private volatile String lastError = null;

    public H264StreamClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setListener(Listener l) { this.listener = l; }

    // ============ Reconnect policy ============

    public void setAutoReconnect(boolean enabled) { this.autoReconnect = enabled; }
    public boolean isAutoReconnect() { return autoReconnect; }

    /** initialMs > 0, maxMs >= initialMs, multiplier >= 1.0. */
    public void setBackoffPolicy(long initialMs, long maxMs, double multiplier) {
        if (initialMs > 0) this.initialBackoffMs = initialMs;
        if (maxMs >= initialMs) this.maxBackoffMs = maxMs;
        if (multiplier >= 1.0) this.backoffMultiplier = multiplier;
    }

    /** 0 = бесконечно; N > 0 = максимум N переподключений (не считая первого). */
    public void setMaxReconnectAttempts(int max) {
        this.maxReconnectAttempts = Math.max(0, max);
    }

    public int getReconnectAttempt() { return reconnectAttempt.get(); }

    // ============ Lifecycle ============

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        reconnectAttempt.set(0);
        lastError = null;
        connectThread = new Thread(this::runLoop, "stream-client");
        connectThread.start();
    }

    private void runLoop() {
        while (running.get()) {
            int attempt = reconnectAttempt.get();

            if (attempt > 0) {
                long delay = computeBackoff(attempt - 1);
                if (listener != null) {
                    listener.onReconnecting(host, port, attempt, delay);
                }
                if (!sleepInterruptibly(delay)) break;
                if (!running.get()) break;
            } else {
                if (listener != null) listener.onConnecting(host, port);
            }

            if (maxReconnectAttempts > 0 && attempt > maxReconnectAttempts) {
                Log.w(TAG, "reconnect: max attempts reached (" + attempt + "), giving up");
                if (listener != null) {
                    listener.onReconnectGaveUp(lastError != null ? lastError : "unknown");
                }
                break;
            }

            try {
                long t0 = System.currentTimeMillis();
                doConnect();
                long timeTaken = System.currentTimeMillis() - t0;

                framesSent = 0;
                bytesSent = 0;
                connected.set(true);
                reconnectAttempt.set(0);
                lastError = null;
                Log.i(TAG, "connected in " + timeTaken + " ms to " + host + ":" + port);
                if (listener != null) listener.onConnected(host + ":" + port);

                waitForDisconnect();
            } catch (ConnectException e) {
                lastError = "Соединение отклонено. Сервер не запущен или порт закрыт.";
                Log.e(TAG, lastError);
                if (listener != null) listener.onError(lastError);
            } catch (SocketTimeoutException e) {
                lastError = "Таймаут подключения. Сервер недоступен.";
                Log.e(TAG, lastError);
                if (listener != null) listener.onError(lastError);
            } catch (UnknownHostException e) {
                lastError = "Неизвестный хост: " + host;
                Log.e(TAG, lastError);
                if (listener != null) listener.onError(lastError);
            } catch (IOException e) {
                lastError = "Ошибка ввода-вывода: " + e.getMessage();
                Log.e(TAG, lastError, e);
                if (listener != null) listener.onError(lastError);
            } catch (Exception e) {
                lastError = "Unexpected: " + e;
                Log.e(TAG, lastError, e);
                if (listener != null) listener.onError(lastError);
            }

            if (!running.get()) break;

            if (!autoReconnect) {
                Log.i(TAG, "auto-reconnect disabled, exiting runLoop");
                break;
            }

            reconnectAttempt.incrementAndGet();
        }

        doDisconnect("run loop ended");
        running.set(false);
    }

    /** retryIndex 0 → initial, 1 → initial*mult, 2 → initial*mult², … ± 20% jitter. */
    private long computeBackoff(int retryIndex) {
        long b = initialBackoffMs;
        for (int i = 0; i < retryIndex && b < maxBackoffMs; i++) {
            b = (long) (b * backoffMultiplier);
        }
        if (b > maxBackoffMs) b = maxBackoffMs;
        long jitter = (long) (b * 0.2 * (Math.random() * 2 - 1));
        return Math.max(100, b + jitter);
    }

    /** Прерываемый сон: просыпается каждые 100 мс, чтобы отреагировать на stop(). */
    private boolean sleepInterruptibly(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (!running.get()) return false;
            try {
                long remaining = deadline - System.currentTimeMillis();
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return running.get();
    }

    private void doConnect() throws IOException {
        Socket s = new Socket();
        Log.i(TAG, "connecting to " + host + ":" + port);
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        synchronized (writeLock) {
            socket = s;
            out = s.getOutputStream();
        }
    }

    private void waitForDisconnect() {
        synchronized (stateLock) {
            while (running.get() && connected.get()) {
                try {
                    stateLock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ============ Data ============

    public void sendFrame(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return;
        if (!connected.get()) return;
        String failMsg = null;
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) return;
            try {
                o.write(annexB);
                o.flush();
                framesSent++;
                bytesSent += annexB.length;
            } catch (IOException e) {
                failMsg = "write: " + e.getMessage();
                Log.w(TAG, failMsg);
            }
        }
        if (failMsg != null) doDisconnect(failMsg);
    }

    // ============ Disconnect ============

    private void doDisconnect(String reason) {
        boolean wasConnected = connected.getAndSet(false);
        synchronized (writeLock) {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            out = null;
            socket = null;
        }
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
        if (wasConnected) {
            Log.i(TAG, "disconnected: " + reason);
            if (listener != null) listener.onDisconnected(reason);
        }
    }

    // ============ Stop ============

    public void stop() {
        running.set(false);
        doDisconnect("stop requested");
        synchronized (stateLock) { stateLock.notifyAll(); }
        Thread t = connectThread;
        connectThread = null;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    public boolean isConnected() { return connected.get(); }
    public long getFramesSent() { return framesSent; }
    public long getBytesSent() { return bytesSent; }
}