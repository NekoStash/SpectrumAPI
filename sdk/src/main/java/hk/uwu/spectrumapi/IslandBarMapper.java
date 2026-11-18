package hk.uwu.spectrumapi;

import java.util.Arrays;

/**
 * Maps dBFS spectrum bands to a small set of normalized visual bars. Adjacent bands are
 * combined in linear power before converting back to dBFS. This mapper is not thread-safe;
 * keep one mapper per independent subscription. Mapping performs no allocation.
 */
public final class IslandBarMapper {
    private final float[] levels;
    private final float floorDb;
    private final float ceilingDb;
    private final float gamma;
    private final double attackSeconds;
    private final double releaseSeconds;
    private long previousTimeNs;

    public IslandBarMapper(int bars) { this(bars, -70, 0, 0.7f, 20, 150); }

    public IslandBarMapper(int bars, float floorDb, float ceilingDb, float gamma,
            float attackMs, float releaseMs) {
        if (bars < 1 || bars > 32 || !Float.isFinite(floorDb) || !Float.isFinite(ceilingDb)
                || ceilingDb <= floorDb || !Float.isFinite(gamma) || gamma <= 0
                || !Float.isFinite(attackMs) || attackMs <= 0
                || !Float.isFinite(releaseMs) || releaseMs <= 0) {
            throw new IllegalArgumentException("Invalid mapper configuration");
        }
        levels = new float[bars];
        this.floorDb = floorDb;
        this.ceilingDb = ceilingDb;
        this.gamma = gamma;
        attackSeconds = attackMs / 1000.0;
        releaseSeconds = releaseMs / 1000.0;
    }

    public int getBarCount() { return levels.length; }
    public void reset() { Arrays.fill(levels, 0); previousTimeNs = 0; }

    /** Uses the producer's monotonic analysis timestamp to derive smoothing dt. */
    public void mapInto(ReusableSpectrumFrame frame, float[] out) {
        long time = frame.getAnalysisTimeNs();
        double dt = previousTimeNs == 0 || time <= previousTimeNs ? 1.0 / 30
                : Math.min(1, (time - previousTimeNs) / 1_000_000_000.0);
        previousTimeNs = time;
        mapInto(frame, out, dt);
    }

    /** Explicit dt variant for a renderer with its own monotonic clock. */
    public void mapInto(ReusableSpectrumFrame frame, float[] out, double dtSeconds) {
        if (out.length < levels.length) throw new IllegalArgumentException("Output is too short");
        if (!Double.isFinite(dtSeconds) || dtSeconds < 0) throw new IllegalArgumentException("Invalid dt");
        int count = frame.getBandCount();
        boolean silent = count == 0 || frame.getStatus() == SpectrumStatus.SILENT
                || frame.getStatus() == SpectrumStatus.IDLE || frame.getStatus() == SpectrumStatus.DISCONNECTED
                || frame.getPeakDb() <= floorDb;
        if (silent) {
            Arrays.fill(levels, 0);
            Arrays.fill(out, 0);
            return;
        }
        for (int bar = 0; bar < levels.length; bar++) {
            // Fractional overlaps avoid empty bars when there are fewer input bands than bars.
            double from = (double) bar * count / levels.length;
            double to = (double) (bar + 1) * count / levels.length;
            double energy = 0;
            for (int band = (int) from; band < Math.min(count, (int) Math.ceil(to)); band++) {
                double weight = Math.max(0, Math.min(to, band + 1.0) - Math.max(from, band));
                energy += Math.pow(10, Math.min(120, frame.getBandDb(band)) / 10.0) * weight;
            }
            energy /= to - from;
            double db = energy > 0 ? 10 * Math.log10(energy) : floorDb;
            double normalized = Math.max(0, Math.min(1, (db - floorDb) / (ceilingDb - floorDb)));
            double target = Math.pow(normalized, gamma);
            double tau = target > levels[bar] ? attackSeconds : releaseSeconds;
            double alpha = -Math.expm1(-Math.min(dtSeconds, 1) / tau);
            levels[bar] += (float) ((target - levels[bar]) * alpha);
            out[bar] = levels[bar];
        }
        Arrays.fill(out, levels.length, out.length, 0);
    }
}
