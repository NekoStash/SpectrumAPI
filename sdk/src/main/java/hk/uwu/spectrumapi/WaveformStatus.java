package hk.uwu.spectrumapi;

/** Waveform availability, independent of the spectrum/session state. */
public final class WaveformStatus {
    public static final int NONE = 0;
    public static final int READY = 1;
    public static final int WARMING_UP = 2;
    public static final int DISCONTINUITY = 3;
    public static final int IDLE = 4;
    public static final int UNSUPPORTED_MULTISOURCE = 5;
    public static final int UNSUPPORTED_PATH = 6;

    private WaveformStatus() { }
}
