package hk.uwu.spectrumapi;

/**
 * Caller-owned storage for allocation-free pulls. Not thread-safe. Listener frames are
 * borrowed until onFrame returns; snapshot() explicitly allocates an independent copy.
 * Spectrum bands, RMS and peak are dBFS. Waveform min/max values are signed source-domain
 * extrema across channels over a recent approximately 40 ms window, not a coherent final
 * mix or replayable PCM; opposite-phase channels therefore do not cancel each other.
 */
public final class ReusableSpectrumFrame {
    public static final int WAVEFORM_BINS = 64;
    public static final int WAVEFORM_FLOATS = WAVEFORM_BINS * 2;

    final float[] bands = new float[32];
    final float[] waveform = new float[WAVEFORM_FLOATS];
    Object streamIdentity;
    long sequence;
    long analysisTimeNs;
    long presentationTimeNs = -1;
    long generation;
    int status = SpectrumStatus.IDLE;
    int coverage;
    int dropped;
    int actualAnalysisRateHz;
    int bandCount;
    float rmsDb = -120;
    float peakDb = -120;
    int outputMode = SpectrumOptions.OUTPUT_SPECTRUM;
    int waveformKind = WaveformKind.NONE;
    int waveformStatus = WaveformStatus.NONE;
    int waveformBinCount;
    int waveformSourceRateHz;
    long waveformTrackToken;
    long waveformSourceEpoch;
    long waveformEndSourceSequence;
    int waveformWindowFrames;
    int waveformFlags;
    long waveformCaptureEndTimeNs;
    long waveformWindowDurationNs;

    public long getSequence() { return sequence; }
    public long getAnalysisTimeNs() { return analysisTimeNs; }
    /** Producer presentation timestamp, or -1 when unknown; not waveform capture time. */
    public long getPresentationTimeNs() { return presentationTimeNs; }
    public long getGeneration() { return generation; }
    public int getStatus() { return status; }
    /** Coverage in parts per thousand: 1000 is all matching active sources, 0 is none. */
    public int getCoverage() { return coverage; }
    /** Raw unsigned-u32 bit pattern; prefer getDroppedUnsigned() for the numeric count. */
    public int getDropped() { return dropped; }
    /** Dropped frames since the previous publish, saturated at 0xffffffff by the producer. */
    public long getDroppedUnsigned() { return Integer.toUnsignedLong(dropped); }
    /** Zero for waveform-only output; the waveform source rate is reported separately. */
    public int getActualAnalysisRateHz() { return actualAnalysisRateHz; }
    /** Zero for waveform-only output. */
    public int getBandCount() { return bandCount; }
    public float getRmsDb() { return rmsDb; }
    public float getPeakDb() { return peakDb; }
    public float getBandDb(int index) {
        if (index < 0 || index >= bandCount) throw new IndexOutOfBoundsException("band index");
        return bands[index];
    }
    /** Copies valid band dBFS values and clears the remainder of existing caller storage. */
    public void copyBandDbInto(float[] destination) {
        if (destination.length < bandCount) throw new IllegalArgumentException("Destination too short");
        System.arraycopy(bands, 0, destination, 0, bandCount);
        java.util.Arrays.fill(destination, bandCount, destination.length, 0);
    }

    public int getOutputMode() { return outputMode; }
    public int getWaveformKind() { return waveformKind; }
    /** Independent of getStatus(): combined output may have valid spectrum but no waveform. */
    public int getWaveformStatus() { return waveformStatus; }
    /** Number of valid min/max pairs, 0..64. Zero means no waveform data. */
    public int getWaveformBinCount() { return waveformBinCount; }
    public float getWaveformMin(int index) { checkWaveformIndex(index); return waveform[index * 2]; }
    public float getWaveformMax(int index) { checkWaveformIndex(index); return waveform[index * 2 + 1]; }
    /** Original captured Track rate, independent of the spectrum analysis rate. */
    public int getWaveformSourceRateHz() { return waveformSourceRateHz; }
    public long getWaveformTrackToken() { return waveformTrackToken; }
    public long getWaveformSourceEpoch() { return waveformSourceEpoch; }
    public long getWaveformEndSourceSequence() { return waveformEndSourceSequence; }
    /** Raw unsigned-u32 frame count. */
    public int getWaveformWindowFrames() { return waveformWindowFrames; }
    public long getWaveformWindowFramesUnsigned() { return Integer.toUnsignedLong(waveformWindowFrames); }
    public int getWaveformFlags() { return waveformFlags; }
    public long getWaveformFlagsUnsigned() { return Integer.toUnsignedLong(waveformFlags); }
    /** Monotonic capture-window end time, NOT a presentation/playback timestamp. */
    public long getWaveformCaptureEndTimeNs() { return waveformCaptureEndTimeNs; }
    public long getWaveformWindowDurationNs() { return waveformWindowDurationNs; }
    /** Copies exactly 128 interleaved min/max floats, including zeroed unused bins. */
    public void copyWaveformInto(float[] destination) {
        if (destination.length < WAVEFORM_FLOATS) throw new IllegalArgumentException("Need 128 waveform floats");
        System.arraycopy(waveform, 0, destination, 0, WAVEFORM_FLOATS);
    }
    private void checkWaveformIndex(int index) {
        if (index < 0 || index >= waveformBinCount) throw new IndexOutOfBoundsException("waveform bin index");
    }

    /** Explicitly allocates an independent frame plus its spectrum and waveform buffers. */
    public ReusableSpectrumFrame snapshot() {
        ReusableSpectrumFrame copy = new ReusableSpectrumFrame();
        copy.streamIdentity = streamIdentity;
        copy.sequence = sequence;
        copy.analysisTimeNs = analysisTimeNs;
        copy.presentationTimeNs = presentationTimeNs;
        copy.generation = generation;
        copy.status = status;
        copy.coverage = coverage;
        copy.dropped = dropped;
        copy.actualAnalysisRateHz = actualAnalysisRateHz;
        copy.bandCount = bandCount;
        copy.rmsDb = rmsDb;
        copy.peakDb = peakDb;
        copy.outputMode = outputMode;
        copy.waveformKind = waveformKind;
        copy.waveformStatus = waveformStatus;
        copy.waveformBinCount = waveformBinCount;
        copy.waveformSourceRateHz = waveformSourceRateHz;
        copy.waveformTrackToken = waveformTrackToken;
        copy.waveformSourceEpoch = waveformSourceEpoch;
        copy.waveformEndSourceSequence = waveformEndSourceSequence;
        copy.waveformWindowFrames = waveformWindowFrames;
        copy.waveformFlags = waveformFlags;
        copy.waveformCaptureEndTimeNs = waveformCaptureEndTimeNs;
        copy.waveformWindowDurationNs = waveformWindowDurationNs;
        System.arraycopy(bands, 0, copy.bands, 0, bands.length);
        System.arraycopy(waveform, 0, copy.waveform, 0, waveform.length);
        return copy;
    }
}
