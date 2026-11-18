package hk.uwu.spectrumapi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-shared, lazy workers and one timer. Idle threads expire; no reconnect loop. */
final class SdkRuntime {
    static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ThreadFactory THREADS = task -> {
        Thread thread = new Thread(task, "SpectrumSDK-" + IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
            0, 2, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), THREADS);
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, THREADS);

    static {
        IO.setCorePoolSize(2);
        IO.allowCoreThreadTimeOut(true);
        TIMER.setKeepAliveTime(20, TimeUnit.SECONDS);
        TIMER.allowCoreThreadTimeOut(true);
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private SdkRuntime() { }
    static void execute(Runnable task) { IO.execute(task); }
    static ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        return TIMER.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }
    static ScheduledFuture<?> poll(Runnable task, long delayMs) {
        return TIMER.scheduleWithFixedDelay(task, 0, delayMs, TimeUnit.MILLISECONDS);
    }
    static <T> CompletableFuture<T> timedFuture(long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ScheduledFuture<?> timeout = schedule(() -> future.completeExceptionally(
                new SpectrumException(SpectrumException.Reason.TIMEOUT, "Spectrum operation timed out")), timeoutMs);
        future.whenComplete((value, error) -> timeout.cancel(false));
        return future;
    }
    static SpectrumException failure(Throwable cause) {
        if (cause instanceof SpectrumException) return (SpectrumException) cause;
        return new SpectrumException(SpectrumException.Reason.REMOTE_ERROR,
                "Spectrum Binder operation failed", cause);
    }
}
