package hk.uwu.spectrumapi.examples;

import android.app.Application;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import hk.uwu.spectrumapi.AnalysisRate;
import hk.uwu.spectrumapi.ReusableSpectrumFrame;
import hk.uwu.spectrumapi.SourceSelector;
import hk.uwu.spectrumapi.SpectrumClient;
import hk.uwu.spectrumapi.SpectrumOptions;
import hk.uwu.spectrumapi.SpectrumSubscription;

/**
 * Framework-independent Java helper for an Xposed/other Hook module. Call onHostReady
 * after obtaining the actual host Application; no Xposed dependency is needed here.
 * Include SDK and protocol classes in the module and use its appropriate class loader.
 * Discovery needs neither a Manager APK nor manifest queries. The SDK uses one non-SDK
 * ServiceManager.checkService reflection; a ROM may deny it, reported asynchronously without
 * exemptions or fallback. Application is used only for UI callbacks.
 */
public final class HookSpectrumHelper implements AutoCloseable {
    private final Executor callbacks;
    private CompletableFuture<SpectrumClient> connecting;
    private CompletableFuture<SpectrumSubscription> opening;
    private SpectrumClient client;
    private SpectrumSubscription subscription;
    private volatile SpectrumSubscription.Listener renderer;
    private boolean visible;
    private boolean closed;

    /** Safe to call from a Hook callback with no Looper: immediately returns a handle. */
    public static HookSpectrumHelper onHostReady(Application application) {
        HookSpectrumHelper helper = new HookSpectrumHelper(application.getMainExecutor());
        helper.connect();
        return helper;
    }

    private HookSpectrumHelper(Executor callbacks) { this.callbacks = callbacks; }

    private void connect() {
        connecting = SpectrumClient.connect();
        connecting.whenCompleteAsync((connected, error) -> {
            synchronized (this) {
                if (closed) { if (connected != null) connected.close(); return; }
                if (error != null) { onError(error); return; }
                client = connected;
                SpectrumOptions options = SpectrumOptions.builder().source(SourceSelector.app())
                        .analysisRate(AnalysisRate.auto(24000)).updateRateHz(30).bandCount(16)
                        .outputMode(SpectrumOptions.OUTPUT_COMBINED).build();
                opening = client.subscribe(options);
                opening.whenCompleteAsync((opened, failure) -> {
                    synchronized (this) {
                        if (closed) { if (opened != null) opened.close(); return; }
                        if (failure != null) { onError(failure); return; }
                        subscription = opened;
                        opened.setListener(callbacks, new SpectrumSubscription.Listener() {
                            @Override public void onFrame(ReusableSpectrumFrame frame) {
                                SpectrumSubscription.Listener current = renderer;
                                if (current != null) current.onFrame(frame);
                                else android.util.Log.v("SpectrumHook", "dBFS peak=" + frame.getPeakDb()
                                        + " waveStatus=" + frame.getWaveformStatus()
                                        + " sourceRate=" + frame.getWaveformSourceRateHz());
                            }
                            @Override public void onStateChanged(int status, long generation) {
                                android.util.Log.d("SpectrumHook", "state=" + status + " generation=" + generation);
                                SpectrumSubscription.Listener current = renderer;
                                if (current != null) current.onStateChanged(status, generation);
                            }
                        });
                        opened.setActive(visible).exceptionally(problem -> { onError(problem); return null; });
                    }
                }, callbacks);
            }
        }, callbacks);
    }

    /**
     * Attach an overlay renderer; callbacks use the host main executor. Frames are borrowed:
     * keep one float[128] and call copyWaveformInto, or synchronously use min/max getters.
     * Draw only READY + MIN_MAX_64; UNSUPPORTED_MULTISOURCE must not synthesize a waveform.
     * Waveform capture time is not presentation time, and this is not replayable/final-mix PCM.
     */
    public void setRenderer(SpectrumSubscription.Listener renderer) { this.renderer = renderer; }

    /** Wire to the injected UI's visible lifecycle, not every audio/Hook invocation. */
    public synchronized void onVisible() {
        visible = true;
        if (!closed && subscription != null) subscription.setActive(true);
    }
    /** Pauses publication and polling while the Island/overlay/UI is hidden. */
    public synchronized void onHidden() {
        visible = false;
        if (!closed && subscription != null) subscription.setActive(false);
    }
    /** Dispose when the overlay/owner is removed; there is no automatic reconnect loop. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connecting != null) connecting.cancel(false);
        if (opening != null) opening.cancel(false);
        if (subscription != null) subscription.close();
        if (client != null) client.close();
    }
    private static void onError(Throwable error) {
        android.util.Log.w("SpectrumHook", "Spectrum unavailable", error);
    }
}
