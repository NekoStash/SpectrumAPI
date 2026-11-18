package hk.uwu.spectrumapi;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import hk.uwu.spectrumapi.ipc.Capabilities;
import hk.uwu.spectrumapi.ipc.ISpectrumService;
import hk.uwu.spectrumapi.ipc.ISpectrumSession;
import hk.uwu.spectrumapi.ipc.ISpectrumStateListener;
import hk.uwu.spectrumapi.ipc.SourceInfo;

/**
 * Asynchronous direct Binder discovery for applications and injected Java code. Discovery
 * reflects only ServiceManager.checkService, a non-SDK entry point that a ROM may restrict;
 * access failures are reported without hidden-API exemptions or alternate discovery paths.
 * No Manager APK, JNI, audio Visualizer, UID grant configuration, or automatic reconnect is used.
 * Cancel a pending connect/subscribe future to release resources created for that attempt.
 * Discovery and Binder calls use shared workers, never a caller thread's Looper or Context.
 */
public final class SpectrumClient implements AutoCloseable {
    private static final String AUDIO_FLINGER_SERVICE = "media.audio_flinger";
    private static final int EXTENSION_TRANSACTION = 0x5f455854;

    private final ISpectrumService service;
    private final IBinder binder;
    private final Set<SpectrumSubscription> subscriptions = new HashSet<>();
    private final IBinder.DeathRecipient deathRecipient = this::onServiceDeath;
    private boolean closed;

    private SpectrumClient(ISpectrumService service) throws RemoteException {
        this.service = service;
        binder = service.asBinder();
        binder.linkToDeath(deathRecipient, 0);
    }

    /** Connects directly to the enabled native module using the default timeout. */
    public static CompletableFuture<SpectrumClient> connect() {
        return connect(SdkRuntime.DEFAULT_TIMEOUT_MS);
    }

    /**
     * Returns promptly; one discovery attempt and handshake run on shared workers, with no
     * Context or caller Looper required. Timeout/cancellation cannot interrupt an in-flight
     * Binder call; a client arriving after either is closed instead of being delivered.
     */
    public static CompletableFuture<SpectrumClient> connect(long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        ConnectAttempt attempt = new ConnectAttempt(timeoutMs);
        SdkRuntime.execute(attempt::discover);
        return attempt.result;
    }

    public CompletableFuture<Capabilities> getCapabilities() {
        return invoke(() -> {
            Capabilities capabilities = service.getCapabilities();
            verifyCapabilities(capabilities);
            return capabilities;
        });
    }

    public CompletableFuture<SourceInfo[]> listSources() {
        return invoke(() -> {
            SourceInfo[] sources = service.listSources();
            if (sources == null) throw unavailable("Service returned null sources");
            return sources;
        });
    }

    /** Opens an initially paused session. Call setActive(true) only while UI is visible. */
    public CompletableFuture<SpectrumSubscription> subscribe(SpectrumOptions options) {
        Objects.requireNonNull(options, "options");
        CompletableFuture<SpectrumSubscription> result = SdkRuntime.timedFuture(SdkRuntime.DEFAULT_TIMEOUT_MS);
        SdkRuntime.execute(() -> {
            ISpectrumSession session = null;
            SharedStreamReader reader = null;
            SpectrumSubscription subscription = null;
            try {
                ensureOpen();
                if (result.isDone()) return;
                StateBridge bridge = new StateBridge();
                session = service.openSession(options.toRequest(), bridge);
                if (session == null || session.asBinder() == null || !session.asBinder().isBinderAlive()) {
                    throw unavailable("Service returned a missing or dead session");
                }
                verifyInterface(session.asBinder(), "hk.uwu.spectrumapi.ipc.ISpectrumSession");
                if (result.isDone()) return;
                session.setActive(false);
                reader = SharedStreamReader.open(session.getStream());
                if (reader.outputMode != options.getOutputMode()) {
                    throw new SpectrumException(SpectrumException.Reason.INVALID_STREAM,
                            "Stream output mode does not match the session request");
                }
                subscription = new SpectrumSubscription(this, session, reader, options);
                reader = null;
                synchronized (this) {
                    ensureOpen();
                    if (subscription.isClosed()) throw unavailable("Session died while opening");
                    subscriptions.add(subscription);
                }
                bridge.attach(subscription);
                if (subscription.isClosed()) throw unavailable("Session disconnected while opening");
                if (!result.complete(subscription)) subscription.close();
                subscription = null;
                session = null;
            } catch (Throwable error) {
                result.completeExceptionally(SdkRuntime.failure(error));
            } finally {
                if (subscription != null) subscription.close();
                if (reader != null) reader.close();
                if (session != null) closeSession(session);
            }
        });
        return result;
    }

    private <T> CompletableFuture<T> invoke(RemoteCall<T> call) {
        CompletableFuture<T> result = SdkRuntime.timedFuture(SdkRuntime.DEFAULT_TIMEOUT_MS);
        SdkRuntime.execute(() -> {
            try {
                ensureOpen();
                if (!result.isDone()) result.complete(call.run());
            } catch (Throwable error) {
                result.completeExceptionally(SdkRuntime.failure(error));
            }
        });
        return result;
    }

    synchronized void remove(SpectrumSubscription subscription) { subscriptions.remove(subscription); }
    synchronized void ensureOpen() {
        if (closed || !binder.isBinderAlive()) throw new SpectrumException(
                SpectrumException.Reason.CLOSED, "Spectrum client is closed or disconnected");
    }
    public synchronized boolean isClosed() { return closed; }

    private void onServiceDeath() { closeInternal(true); }
    @Override public void close() { closeInternal(false); }

    private void closeInternal(boolean disconnected) {
        ArrayList<SpectrumSubscription> current;
        synchronized (this) {
            if (closed) return;
            closed = true;
            current = new ArrayList<>(subscriptions);
            subscriptions.clear();
        }
        try { binder.unlinkToDeath(deathRecipient, 0); } catch (RuntimeException ignored) { }
        for (SpectrumSubscription subscription : current) {
            if (disconnected) subscription.disconnected(); else subscription.close();
        }
    }

    static void closeSession(ISpectrumSession session) {
        try { session.close(); } catch (RemoteException | RuntimeException ignored) { }
    }
    private static void verifyCapabilities(Capabilities capabilities) {
        if (capabilities == null) throw unavailable("Service returned no capabilities");
        if (capabilities.protocolVersion != SharedStreamReader.VERSION
                || capabilities.waveformBinCount != ReusableSpectrumFrame.WAVEFORM_BINS) {
            throw new SpectrumException(SpectrumException.Reason.PROTOCOL_MISMATCH,
                    "Service does not implement the current fixed spectrum/waveform contract");
        }
    }
    private static void verifyInterface(IBinder binder, String expected) throws RemoteException {
        if (!expected.equals(binder.getInterfaceDescriptor())) {
            throw new SpectrumException(SpectrumException.Reason.PROTOCOL_MISMATCH,
                    "Binder does not implement " + expected);
        }
    }
    private static SpectrumException unavailable(String message) {
        return new SpectrumException(SpectrumException.Reason.SERVICE_UNAVAILABLE, message);
    }
    private interface RemoteCall<T> { T run() throws RemoteException; }

    /** Buffers an initial oneway state event that may precede openSession's return. */
    private static final class StateBridge extends ISpectrumStateListener.Stub {
        private SpectrumSubscription subscription;
        private int status;
        private long generation;
        private boolean seen;
        @Override public synchronized void onStateChanged(int status, long generation) {
            this.status = status;
            this.generation = generation;
            seen = true;
            if (subscription != null) subscription.stateChanged(status, generation);
        }
        synchronized void attach(SpectrumSubscription subscription) {
            this.subscription = subscription;
            if (seen) subscription.stateChanged(status, generation);
        }
    }

    private static IBinder findAudioFlinger() {
        final Object service;
        try {
            // This is the sole non-SDK entry point. Do not exempt hidden APIs, force
            // accessibility, retry, or substitute another discovery mechanism.
            service = Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, AUDIO_FLINGER_SERVICE);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw discoveryUnavailable(cause != null ? cause : error);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            throw discoveryUnavailable(error);
        }
        if (service == null) throw unavailable("AudioFlinger service is unavailable");
        if (!(service instanceof IBinder)) {
            throw new SpectrumException(SpectrumException.Reason.PROTOCOL_MISMATCH,
                    "ServiceManager returned a non-Binder AudioFlinger service");
        }
        IBinder audioFlinger = (IBinder) service;
        if (!audioFlinger.isBinderAlive()) throw unavailable("AudioFlinger Binder is dead");
        return audioFlinger;
    }

    private static SpectrumException discoveryUnavailable(Throwable cause) {
        return new SpectrumException(SpectrumException.Reason.DISCOVERY_UNAVAILABLE,
                "Cannot access ServiceManager.checkService from this host; no alternate discovery is used", cause);
    }

    private static IBinder findExtension(IBinder audioFlinger) throws RemoteException {
        Parcel request = Parcel.obtain();
        try {
            Parcel reply = Parcel.obtain();
            try {
                // Native Binder _EXT has neither an AIDL token nor an exception header.
                if (!audioFlinger.transact(EXTENSION_TRANSACTION, request, reply, 0)) {
                    throw unavailable("AudioFlinger Binder does not expose an extension");
                }
                IBinder extension = reply.readStrongBinder();
                if (extension == null || !extension.isBinderAlive()) {
                    throw unavailable("Spectrum service extension is unavailable");
                }
                return extension;
            } finally {
                reply.recycle();
            }
        } finally {
            request.recycle();
        }
    }

    private static final class ConnectAttempt {
        final CompletableFuture<SpectrumClient> result;

        ConnectAttempt(long timeoutMs) {
            result = SdkRuntime.timedFuture(timeoutMs);
        }

        void discover() {
            SpectrumClient client = null;
            try {
                if (result.isDone()) return;
                IBinder audioFlinger = findAudioFlinger();
                if (result.isDone()) return;
                IBinder serviceBinder = findExtension(audioFlinger);
                if (result.isDone()) return;
                verifyInterface(serviceBinder, "hk.uwu.spectrumapi.ipc.ISpectrumService");
                ISpectrumService service = ISpectrumService.Stub.asInterface(serviceBinder);
                if (service == null) throw unavailable("Missing spectrum service interface");
                if (result.isDone()) return;
                client = new SpectrumClient(service);
                verifyCapabilities(service.getCapabilities());
                client.ensureOpen();
                if (result.complete(client)) client = null;
            } catch (Throwable error) {
                result.completeExceptionally(SdkRuntime.failure(error));
            } finally {
                if (client != null) client.close();
            }
        }
    }
}
