package hk.uwu.spectrumapi;

/** Immutable per-session analysis rate request, not a device-global sampling setting. */
public final class AnalysisRate {
    public static final int AUTO = 0;
    public static final int SOURCE = 1;
    public static final int EXACT = 2;
    public static final int AT_MOST = 3;

    private final int policy;
    private final int hz;

    private AnalysisRate(int policy, int hz) {
        if (hz <= 0 || hz > 192000) throw new IllegalArgumentException("Invalid analysis rate");
        this.policy = policy;
        this.hz = hz;
    }

    public static AnalysisRate auto(int preferredHz) { return new AnalysisRate(AUTO, preferredHz); }
    public static AnalysisRate source() { return new AnalysisRate(SOURCE, 24000); }
    public static AnalysisRate exact(int hz) { return new AnalysisRate(EXACT, hz); }
    public static AnalysisRate atMost(int hz) { return new AnalysisRate(AT_MOST, hz); }
    public int getPolicy() { return policy; }
    public int getHz() { return hz; }
}
