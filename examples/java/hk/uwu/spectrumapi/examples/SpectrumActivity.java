package hk.uwu.spectrumapi.examples;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import hk.uwu.spectrumapi.AnalysisRate;
import hk.uwu.spectrumapi.IslandBarMapper;
import hk.uwu.spectrumapi.ReusableSpectrumFrame;
import hk.uwu.spectrumapi.SourceSelector;
import hk.uwu.spectrumapi.SpectrumClient;
import hk.uwu.spectrumapi.SpectrumOptions;
import hk.uwu.spectrumapi.SpectrumStatus;
import hk.uwu.spectrumapi.SpectrumSubscription;
import hk.uwu.spectrumapi.WaveformKind;
import hk.uwu.spectrumapi.WaveformStatus;

/**
 * Three independent sessions demonstrate spectrum, combined, and waveform-only output.
 * The first two have different analysis/publication requests, never global settings.
 * More than one matching active Track makes
 * waveform UNSUPPORTED_MULTISOURCE; combined spectrum remains independently usable.
 * To select one exact Track, use SourceSelector.track(token) from client.listSources().
 */
public final class SpectrumActivity extends Activity {
    private final ArrayList<SpectrumSubscription> subscriptions = new ArrayList<>();
    private final ArrayList<CompletableFuture<SpectrumSubscription>> opening = new ArrayList<>();
    private final ArrayList<SignalView> views = new ArrayList<>();
    private CompletableFuture<SpectrumClient> connecting;
    private SpectrumClient client;
    private TextView diagnostic;
    private boolean visible;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        diagnostic = new TextView(this);
        diagnostic.setText("Connecting; waveform is signed source min/max, not final mix or PCM.");
        content.addView(diagnostic);
        SignalView spectrum = addView(content, "Spectrum: AUTO 24k / 30 Hz", SpectrumOptions.OUTPUT_SPECTRUM);
        SignalView combined = addView(content, "Combined: AT_MOST 48k / 15 Hz", SpectrumOptions.OUTPUT_COMBINED);
        SignalView waveform = addView(content, "Waveform only: 30 Hz; no analysis-rate request", SpectrumOptions.OUTPUT_WAVEFORM);
        setContentView(content);
        connecting = SpectrumClient.connect();
        connecting.whenCompleteAsync((connected, error) -> {
            if (destroyed) { if (connected != null) connected.close(); return; }
            if (error != null) { diagnostic.setText(error.toString()); return; }
            client = connected;
            // Each session retains its own analysis rate, update rate, mode and shared memory.
            open(SpectrumOptions.builder().source(SourceSelector.app())
                    .analysisRate(AnalysisRate.auto(24000)).updateRateHz(30).bandCount(16)
                    .outputMode(SpectrumOptions.OUTPUT_SPECTRUM).build(), spectrum);
            open(SpectrumOptions.builder().source(SourceSelector.app())
                    .analysisRate(AnalysisRate.atMost(48000)).updateRateHz(15).bandCount(32)
                    .outputMode(SpectrumOptions.OUTPUT_COMBINED).build(), combined);
            // Spectrum fields are irrelevant and are not set/parsed for waveform-only.
            open(SpectrumOptions.builder().source(SourceSelector.app()).updateRateHz(30)
                    .outputMode(SpectrumOptions.OUTPUT_WAVEFORM).build(), waveform);
        }, getMainExecutor());
    }

    private SignalView addView(LinearLayout content, String title, int mode) {
        TextView label = new TextView(this);
        label.setText(title);
        content.addView(label);
        SignalView view = new SignalView(mode);
        views.add(view);
        content.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        return view;
    }

    private void open(SpectrumOptions options, SignalView view) {
        CompletableFuture<SpectrumSubscription> future = client.subscribe(options);
        opening.add(future);
        future.whenCompleteAsync((subscription, error) -> {
            if (destroyed) { if (subscription != null) subscription.close(); return; }
            if (error != null) { diagnostic.setText(error.toString()); return; }
            subscriptions.add(subscription);
            subscription.setListener(getMainExecutor(), new SpectrumSubscription.Listener() {
                @Override public void onFrame(ReusableSpectrumFrame frame) { view.accept(frame); }
                @Override public void onStateChanged(int status, long generation) {
                    if (view.generation != generation || status == SpectrumStatus.IDLE
                            || status == SpectrumStatus.SILENT || status == SpectrumStatus.DISCONNECTED) view.clear();
                    view.generation = generation;
                    diagnostic.setText("state=" + status + " generation=" + generation
                            + " spectrum analysis=" + subscription.getActualAnalysisRateHz()
                            + "Hz publish=" + subscription.getActualUpdateRateHz() + "Hz");
                }
            });
            subscription.setActive(visible).exceptionally(failure -> {
                getMainExecutor().execute(() -> { if (!destroyed) diagnostic.setText(failure.toString()); });
                return null;
            });
        }, getMainExecutor());
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        for (SpectrumSubscription subscription : subscriptions) subscription.setActive(true);
    }
    @Override protected void onStop() {
        visible = false;
        for (SpectrumSubscription subscription : subscriptions) subscription.setActive(false);
        for (SignalView view : views) view.clear();
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (connecting != null) connecting.cancel(false);
        for (CompletableFuture<SpectrumSubscription> future : opening) future.cancel(false);
        for (SpectrumSubscription subscription : subscriptions) subscription.close();
        if (client != null) client.close();
        super.onDestroy();
    }

    /** All callback storage is reused. Waveform rendering does not synthesize data from bands. */
    private final class SignalView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final IslandBarMapper mapper = new IslandBarMapper(4);
        private final float[] levels = new float[4];
        private final float[] minMax = new float[ReusableSpectrumFrame.WAVEFORM_FLOATS];
        private int mode;
        private int waveKind;
        private int waveStatus = WaveformStatus.IDLE;
        private int bins;
        private int sourceRate;
        private long track;
        private long epoch;
        private long generation;
        private String waveLabel = "waveform idle";

        SignalView(int mode) {
            super(SpectrumActivity.this);
            this.mode = mode;
            paint.setTextSize(28);
        }
        void clear() {
            mapper.reset();
            Arrays.fill(levels, 0);
            Arrays.fill(minMax, 0);
            bins = 0;
            waveKind = WaveformKind.NONE;
            waveStatus = WaveformStatus.IDLE;
            waveLabel = "waveform idle";
            invalidate();
        }
        void accept(ReusableSpectrumFrame frame) {
            if (generation != frame.getGeneration() || mode != frame.getOutputMode()
                    || track != frame.getWaveformTrackToken() || epoch != frame.getWaveformSourceEpoch()
                    || sourceRate != frame.getWaveformSourceRateHz()) clear();
            mode = frame.getOutputMode();
            generation = frame.getGeneration();
            mapper.mapInto(frame, levels);
            frame.copyWaveformInto(minMax);
            if (waveStatus != frame.getWaveformStatus() || sourceRate != frame.getWaveformSourceRateHz()
                    || track != frame.getWaveformTrackToken() || epoch != frame.getWaveformSourceEpoch()) {
                waveLabel = "wave status=" + frame.getWaveformStatus()
                        + " source=" + frame.getWaveformSourceRateHz() + "Hz";
            }
            waveKind = frame.getWaveformKind();
            waveStatus = frame.getWaveformStatus();
            bins = frame.getWaveformBinCount();
            sourceRate = frame.getWaveformSourceRateHz();
            track = frame.getWaveformTrackToken();
            epoch = frame.getWaveformSourceEpoch();
            // captureEndTimeNs is monotonic capture time, never presentation time. No history
            // or playback is inferred from this recent approximately 40 ms source window.
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean spectrum = mode != SpectrumOptions.OUTPUT_WAVEFORM;
            boolean wave = mode != SpectrumOptions.OUTPUT_SPECTRUM;
            float spectrumHeight = wave ? getHeight() / 2f : getHeight();
            paint.setColor(Color.rgb(80, 190, 255));
            if (spectrum) {
                float step = getWidth() / 4f;
                for (int i = 0; i < levels.length; i++) {
                    float height = levels[i] * spectrumHeight;
                    canvas.drawRect(i * step + 6, spectrumHeight - height,
                            (i + 1) * step - 6, spectrumHeight, paint);
                }
            }
            if (!wave) return;
            float top = spectrum ? spectrumHeight : 0;
            canvas.drawText(waveLabel, 8, top + 30, paint);
            // Invalid/multi-source waveform stays blank; combined spectrum above stays intact.
            if (waveKind != WaveformKind.MIN_MAX_64 || waveStatus != WaveformStatus.READY || bins == 0) return;
            float center = (top + 40 + getHeight()) / 2f;
            float scale = Math.max(0, getHeight() - top - 40) * 0.45f;
            for (int bin = 0; bin < bins; bin++) {
                float x = (bin + 0.5f) * getWidth() / bins;
                float min = Math.max(-1, Math.min(1, minMax[bin * 2]));
                float max = Math.max(-1, Math.min(1, minMax[bin * 2 + 1]));
                canvas.drawLine(x, center - max * scale, x, center - min * scale, paint);
            }
        }
    }
}
