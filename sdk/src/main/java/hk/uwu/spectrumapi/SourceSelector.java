package hk.uwu.spectrumapi;

import android.os.Process;

/** Immutable playback source selection for per-session spectrum and waveform streams. */
public final class SourceSelector {
    public static final int APP = 0;
    public static final int TRACK = 1;
    public static final int ALL = 2;
    public static final int OUTPUT = 3;

    private final int mode;
    private final int uid;
    private final long trackToken;
    private final long outputToken;
    private final int usage;

    private SourceSelector(int mode, int uid, long trackToken, long outputToken, int usage) {
        this.mode = mode;
        this.uid = uid;
        this.trackToken = trackToken;
        this.outputToken = outputToken;
        this.usage = usage;
    }

    public static SourceSelector app() { return app(Process.myUid()); }
    public static SourceSelector app(int uid) {
        if (uid < 0) throw new IllegalArgumentException("uid must be nonnegative");
        return new SourceSelector(APP, uid, 0, 0, 1);
    }
    public static SourceSelector track(long token) {
        if (token <= 0) throw new IllegalArgumentException("track token must be positive");
        return new SourceSelector(TRACK, -1, token, 0, 1);
    }
    public static SourceSelector allMedia() { return new SourceSelector(ALL, -1, 0, 0, 1); }
    public static SourceSelector output(long token) {
        if (token < 0) throw new IllegalArgumentException("output token must be nonnegative");
        return new SourceSelector(OUTPUT, -1, 0, token, 1);
    }
    public SourceSelector withUsage(int usage) {
        if (usage < 0 || usage > 17 || (mode == ALL && usage != 1)) {
            throw new IllegalArgumentException("usage must be 0..17; ALL_MEDIA requires USAGE_MEDIA");
        }
        return new SourceSelector(mode, uid, trackToken, outputToken, usage);
    }
    public int getMode() { return mode; }
    public int getTargetUid() { return uid; }
    public long getTrackToken() { return trackToken; }
    public long getOutputToken() { return outputToken; }
    public int getUsage() { return usage; }
}
