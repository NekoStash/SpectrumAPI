package hk.uwu.spectrumapi;

import android.os.IBinder;
import android.os.RemoteException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import hk.uwu.spectrumapi.ipc.ISpectrumSession;

/**
 * One independent stream and request. Pulls are allocation-free and serialized with unmap.
 * Lifecycle methods return promptly. A timed-out/cancelled in-flight Binder transaction
 * cannot be revoked, so close the subscription to abandon an uncertain remote operation.
 */
public final class SpectrumSubscription implements AutoCloseable {
    public interface Listener {
        /** Borrowed until return. Do not retain or modify this frame across callbacks. */
        void onFrame(ReusableSpectrumFrame frame);
        default void onStateChanged(int status, long generation) { }
    }

    private final SpectrumClient owner;
    private final ISpectrumSession session;
    private final IBinder.DeathRecipient deathRecipient = this::disconnected;
    private final Object operationLock = new Object();
    private CompletableFuture<Void> operationTail = CompletableFuture.completedFuture(null);
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private SharedStreamReader reader;
    private SpectrumOptions options;
    private CallbackPump pump;
    private ScheduledFuture<?> polling;
    private boolean active;
    private boolean requestedActive;
    private boolean closed;
    private int status;
    private long generation;

    SpectrumSubscription(SpectrumClient owner, ISpectrumSession session,
            SharedStreamReader reader, SpectrumOptions options) throws RemoteException {
        this.owner = owner;
        this.session = session;
        this.reader = reader;
        this.options = options;
        status = reader.initialStatus;
        generation = reader.generation;
        session.asBinder().linkToDeath(deathRecipient, 0);
    }

    /** Returns false for no new complete frame or a closed stream; a paused stream may retain its last frame. */
    public synchronized boolean readLatestInto(ReusableSpectrumFrame destination) {
        Objects.requireNonNull(destination, "destination");
        return !closed && reader != null && reader.readLatestInto(destination);
    }

    /** Explicitly allocating convenience pull; null means no published frame is available. */
    public ReusableSpectrumFrame snapshot() {
        ReusableSpectrumFrame frame = new ReusableSpectrumFrame();
        return readLatestInto(frame) ? frame : null;
    }

    public synchronized SpectrumOptions getOptions() { return options; }
    public synchronized int getStatus() { return status; }
    public synchronized long getGeneration() { return generation; }
    public synchronized boolean isClosed() { return closed; }
    public synchronized boolean isActive() { return active && !closed; }
    public synchronized int getActualAnalysisRateHz() { return reader == null ? 0 : reader.actualAnalysisRateHz; }
    public synchronized int getActualUpdateRateHz() { return reader == null ? 0 : reader.actualUpdateRateHz; }
    public synchronized int getOutputMode() { return reader == null ? options.getOutputMode() : reader.outputMode; }
    public synchronized float[] getBandEdgesHz() { return reader == null ? new float[0] : reader.bandEdgesHz.clone(); }

    /** Installs one callback consumer on the caller-chosen executor; null removes it. */
    public void setListener(Executor executor, Listener listener) {
        CallbackPump previous;
        CallbackPump next = listener == null ? null : new CallbackPump(
                Objects.requireNonNull(executor, "executor"), listener);
        int currentStatus;
        long currentGeneration;
        synchronized (this) {
            ensureOpen();
            previous = pump;
            pump = next;
            currentStatus = status;
            currentGeneration = generation;
            restartPollingLocked();
        }
        if (previous != null) previous.stop();
        if (next != null) next.state(currentStatus, currentGeneration);
    }

    /** Pausing immediately stops local polling; server changes run in per-session FIFO order. */
    public CompletableFuture<Void> setActive(boolean enabled) {
        synchronized (this) {
            requestedActive = enabled;
            if (!enabled) { active = false; restartPollingLocked(); }
        }
        return perform(() -> {
            session.setActive(enabled);
            synchronized (this) {
                ensureOpen();
                // A later hide must not be undone locally by an earlier enable completing.
                active = enabled && requestedActive;
                restartPollingLocked();
            }
        });
    }

    /** Only this session is updated. It does not mutate options/rates of any other session. */
    public CompletableFuture<Void> update(SpectrumOptions options) {
        Objects.requireNonNull(options, "options");
        return perform(() -> {
            session.update(options.toRequest());
            refreshReader(options);
        });
    }

    private void refreshReader(SpectrumOptions nextOptions) throws RemoteException {
        SpectrumException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            synchronized (this) { ensureOpen(); }
            try {
                if (replaceReader(SharedStreamReader.open(session.getStream()), nextOptions)) return;
            } catch (SpectrumException error) {
                if (error.getReason() != SpectrumException.Reason.INVALID_STREAM) throw error;
                last = error;
            }
        }
        throw new SpectrumException(SpectrumException.Reason.INVALID_STREAM,
                "Stream changed during three bounded mapping attempts", last);
    }

    private boolean replaceReader(SharedStreamReader next, SpectrumOptions nextOptions) {
        SharedStreamReader previous;
        synchronized (this) {
            if (closed) { next.close(); ensureOpen(); }
            if (Long.compareUnsigned(next.generation, generation) < 0) {
                next.close();
                return false;
            }
            if (nextOptions != null && next.outputMode != nextOptions.getOutputMode()) {
                next.close();
                throw new SpectrumException(SpectrumException.Reason.INVALID_STREAM,
                        "Updated stream output mode does not match the session request");
            }
            previous = reader;
            reader = next;
            if (nextOptions != null) options = nextOptions;
            // A buffered oneway state can already be newer than the descriptor just fetched.
            if (Long.compareUnsigned(next.generation, generation) > 0) {
                generation = next.generation;
                status = next.initialStatus;
            }
            restartPollingLocked();
        }
        if (previous != null) previous.close();
        CallbackPump current;
        int newStatus;
        long newGeneration;
        synchronized (this) { current = pump; newStatus = status; newGeneration = generation; }
        if (current != null) current.state(newStatus, newGeneration);
        return true;
    }

    void stateChanged(int status, long generation) {
        if (status < SpectrumStatus.OK || status > SpectrumStatus.DROPPED) return;
        if (status == SpectrumStatus.DISCONNECTED) { disconnected(); return; }
        CallbackPump current;
        synchronized (this) {
            if (closed || Long.compareUnsigned(generation, this.generation) < 0) return;
            this.status = status;
            this.generation = generation;
            current = pump;
            restartPollingLocked();
        }
        if (current != null) current.state(status, generation);
        refreshIfNeeded();
    }

    private void refreshIfNeeded() {
        synchronized (this) {
            if (closed || reader == null || reader.generation == generation) return;
        }
        if (refreshPending.compareAndSet(false, true)) {
            perform(() -> refreshReader(null)).whenComplete((unused, error) -> {
                refreshPending.set(false);
                if (error != null) { if (!isClosed()) disconnected(); }
                else refreshIfNeeded(); // Coalesce a newer generation received during this fetch.
            });
        }
    }

    private CompletableFuture<Void> perform(SessionOperation operation) {
        CompletableFuture<Void> result = SdkRuntime.timedFuture(SdkRuntime.DEFAULT_TIMEOUT_MS);
        synchronized (operationLock) {
            operationTail = operationTail.handle((unused, error) -> null).thenRunAsync(() -> {
                try {
                    synchronized (this) { ensureOpen(); }
                    owner.ensureOpen();
                    if (!result.isDone()) { operation.run(); result.complete(null); }
                } catch (Throwable error) {
                    result.completeExceptionally(SdkRuntime.failure(error));
                    if (error instanceof android.os.DeadObjectException) disconnected();
                }
            }, SdkRuntime::execute);
        }
        return result;
    }

    private void ensureOpen() {
        if (closed) throw new SpectrumException(SpectrumException.Reason.CLOSED, "Spectrum subscription is closed");
    }

    private boolean shouldPoll() {
        return !closed && active && pump != null && (status == SpectrumStatus.OK
                || status == SpectrumStatus.SILENT || status == SpectrumStatus.PARTIAL || status == SpectrumStatus.DROPPED);
    }
    private void restartPollingLocked() {
        if (polling != null) { polling.cancel(false); polling = null; }
        if (!shouldPoll()) return;
        int hz = reader == null ? options.getUpdateRateHz() : reader.actualUpdateRateHz;
        polling = SdkRuntime.poll(this::poll, Math.max(1, (1000L + hz - 1) / hz));
    }
    private void poll() {
        CallbackPump current;
        synchronized (this) { if (!shouldPoll()) return; current = pump; }
        if (current != null) current.readAndDispatch();
    }

    void disconnected() { closeInternal(true); }
    @Override public void close() { closeInternal(false); }

    private void closeInternal(boolean disconnected) {
        SharedStreamReader previous;
        CallbackPump previousPump;
        long lastGeneration;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = false;
            if (polling != null) { polling.cancel(false); polling = null; }
            previous = reader;
            reader = null;
            previousPump = pump;
            pump = null;
            if (disconnected) status = SpectrumStatus.DISCONNECTED;
            lastGeneration = generation;
        }
        if (previous != null) previous.close();
        try { session.asBinder().unlinkToDeath(deathRecipient, 0); } catch (RuntimeException ignored) { }
        owner.remove(this);
        if (previousPump != null) {
            if (disconnected) previousPump.state(SpectrumStatus.DISCONNECTED, lastGeneration);
            else previousPump.stop();
        }
        SdkRuntime.execute(() -> SpectrumClient.closeSession(session));
    }

    private interface SessionOperation { void run() throws RemoteException; }

    /** At most one queued/running runnable; slow UI executors cannot accumulate frames. */
    private final class CallbackPump {
        final Executor executor;
        final Listener listener;
        final ReusableSpectrumFrame frame = new ReusableSpectrumFrame();
        final Runnable delivery = this::deliver;
        boolean pending;
        boolean stopped;
        boolean frameReady;
        boolean stateDirty;
        int state;
        long stateGeneration;

        CallbackPump(Executor executor, Listener listener) { this.executor = executor; this.listener = listener; }
        void state(int status, long generation) {
            boolean submit;
            synchronized (this) {
                if (stopped) return;
                state = status;
                stateGeneration = generation;
                stateDirty = true;
                if (status == SpectrumStatus.IDLE || status == SpectrumStatus.DISCONNECTED
                        || (frameReady && frame.getGeneration() != generation)) frameReady = false;
                submit = !pending;
                if (submit) pending = true;
            }
            if (submit) enqueue();
        }
        void readAndDispatch() {
            synchronized (this) {
                if (stopped || pending || !SpectrumSubscription.this.readLatestInto(frame)) return;
                frameReady = true;
                pending = true;
            }
            enqueue();
        }
        void enqueue() {
            try { executor.execute(delivery); }
            catch (RuntimeException rejected) { stop(); }
        }
        void deliver() {
            boolean sendState;
            boolean sendFrame;
            int currentState;
            long currentGeneration;
            synchronized (this) {
                if (stopped) { pending = false; return; }
                sendState = stateDirty;
                sendFrame = frameReady;
                currentState = state;
                currentGeneration = stateGeneration;
                stateDirty = false;
                frameReady = false;
            }
            try {
                if (sendState) listener.onStateChanged(currentState, currentGeneration);
                if (sendFrame) {
                    synchronized (this) { if (stopped) return; }
                    if (SpectrumSubscription.this.isActive()) listener.onFrame(frame);
                }
            } catch (RuntimeException ignored) {
                // A client callback must not terminate the shared process timer or worker.
            } finally {
                boolean again;
                synchronized (this) {
                    pending = false;
                    again = !stopped && stateDirty;
                    if (again) pending = true;
                }
                if (again) enqueue();
            }
        }
        void stop() {
            synchronized (this) { stopped = true; stateDirty = false; frameReady = false; }
            synchronized (SpectrumSubscription.this) {
                if (pump == this) { pump = null; restartPollingLocked(); }
            }
        }
    }
}
