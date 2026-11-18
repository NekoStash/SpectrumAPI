package hk.uwu.spectrumapi;

import java.util.Objects;
import hk.uwu.spectrumapi.ipc.SpectrumRequest;

/** Immutable per-session options; analysis and publication rates are never device-global. */
public final class SpectrumOptions {
    public static final int OUTPUT_SPECTRUM = 0;
    public static final int OUTPUT_WAVEFORM = 1;
    public static final int OUTPUT_COMBINED = 2;

    private final SourceSelector source;
    private final AnalysisRate analysisRate;
    private final int updateRateHz;
    private final int bandCount;
    private final float minFrequencyHz;
    private final float maxFrequencyHz;
    private final int outputMode;

    private SpectrumOptions(Builder builder) {
        source = builder.source;
        analysisRate = builder.analysisRate;
        updateRateHz = builder.updateRateHz;
        bandCount = builder.bandCount;
        minFrequencyHz = builder.minFrequencyHz;
        maxFrequencyHz = builder.maxFrequencyHz;
        outputMode = builder.outputMode;
    }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return new Builder().source(source).analysisRate(analysisRate).updateRateHz(updateRateHz)
                .bandCount(bandCount).frequencyRange(minFrequencyHz, maxFrequencyHz).outputMode(outputMode);
    }
    public SourceSelector getSource() { return source; }
    /** Requested spectrum analysis rate; ignored for OUTPUT_WAVEFORM. */
    public AnalysisRate getAnalysisRate() { return analysisRate; }
    public int getUpdateRateHz() { return updateRateHz; }
    /** Requested spectrum bands; ignored for OUTPUT_WAVEFORM. */
    public int getBandCount() { return bandCount; }
    public float getMinFrequencyHz() { return minFrequencyHz; }
    public float getMaxFrequencyHz() { return maxFrequencyHz; }
    public int getOutputMode() { return outputMode; }

    SpectrumRequest toRequest() {
        SpectrumRequest request = new SpectrumRequest();
        request.sourceMode = source.getMode();
        request.targetUid = source.getTargetUid();
        request.trackToken = source.getTrackToken();
        request.outputToken = source.getOutputToken();
        request.usage = source.getUsage();
        request.updateRateHz = updateRateHz;
        request.outputMode = outputMode;
        boolean spectrum = outputMode != OUTPUT_WAVEFORM;
        request.analysisRateHz = spectrum ? analysisRate.getHz() : 0;
        request.analysisRatePolicy = spectrum ? analysisRate.getPolicy() : AnalysisRate.AUTO;
        request.bandCount = spectrum ? bandCount : 0;
        request.minFrequencyHz = spectrum ? minFrequencyHz : 0;
        request.maxFrequencyHz = spectrum ? maxFrequencyHz : 0;
        return request;
    }

    public static final class Builder {
        private SourceSelector source = SourceSelector.app();
        private AnalysisRate analysisRate = AnalysisRate.auto(24000);
        private int updateRateHz = 30;
        private int bandCount = 16;
        private float minFrequencyHz = 40;
        private float maxFrequencyHz = 8000;
        private int outputMode = OUTPUT_SPECTRUM;

        public Builder source(SourceSelector source) { this.source = Objects.requireNonNull(source); return this; }
        public Builder analysisRate(AnalysisRate rate) { analysisRate = Objects.requireNonNull(rate); return this; }
        public Builder updateRateHz(int hz) {
            if (hz < 1 || hz > 60) throw new IllegalArgumentException("updateRateHz must be 1..60");
            updateRateHz = hz;
            return this;
        }
        /** Spectrum validation occurs at build(), so waveform-only ignores unused fields. */
        public Builder bandCount(int count) { bandCount = count; return this; }
        /** Spectrum validation occurs at build(), independently of setter order. */
        public Builder frequencyRange(float minHz, float maxHz) {
            minFrequencyHz = minHz;
            maxFrequencyHz = maxHz;
            return this;
        }
        public Builder outputMode(int mode) {
            if (mode < OUTPUT_SPECTRUM || mode > OUTPUT_COMBINED) {
                throw new IllegalArgumentException("outputMode must be SPECTRUM, WAVEFORM, or COMBINED");
            }
            outputMode = mode;
            return this;
        }
        public SpectrumOptions build() {
            if (outputMode != OUTPUT_WAVEFORM) {
                if (bandCount != 8 && bandCount != 16 && bandCount != 32) {
                    throw new IllegalArgumentException("bandCount must be 8, 16, or 32");
                }
                if (!Float.isFinite(minFrequencyHz) || !Float.isFinite(maxFrequencyHz)
                        || minFrequencyHz <= 0 || maxFrequencyHz <= minFrequencyHz || maxFrequencyHz > 96000) {
                    throw new IllegalArgumentException("Invalid spectrum frequency range");
                }
            }
            return new SpectrumOptions(this);
        }
    }
}
