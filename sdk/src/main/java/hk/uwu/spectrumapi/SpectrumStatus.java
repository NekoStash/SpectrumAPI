package hk.uwu.spectrumapi;

/** Stable wire status values. OK is the active state; IDLE suspends callback polling. */
public final class SpectrumStatus {
    public static final int OK = 0;
    public static final int IDLE = 1;
    public static final int SILENT = 2;
    public static final int PARTIAL = 3;
    public static final int UNSUPPORTED_PATH = 4;
    public static final int UNSUPPORTED_BUILD = 5;
    public static final int DISCONNECTED = 6;
    public static final int DROPPED = 7;

    private SpectrumStatus() { }
}
