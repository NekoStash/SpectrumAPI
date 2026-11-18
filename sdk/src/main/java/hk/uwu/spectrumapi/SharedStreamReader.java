package hk.uwu.spectrumapi;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import hk.uwu.spectrumapi.ipc.StreamDescriptor;

/** Reads the current fixed layout only. Reading and unmapping share this object's monitor. */
final class SharedStreamReader implements AutoCloseable {
    static final int MAGIC = 0x53504354;
    static final int VERSION = 1;
    static final int MAPPED_BYTES = 8192;
    static final int HEADER_BYTES = 64;
    static final int STRIDE = 768;
    static final int SLOTS = 8;
    private static final VarHandle INTS = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONGS = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private ByteBuffer memory;
    private final Object identity = new Object();
    private final float[] spectrumScratch = new float[32];
    private final float[] waveformScratch = new float[ReusableSpectrumFrame.WAVEFORM_FLOATS];
    final int bandCount;
    final int actualAnalysisRateHz;
    final int actualUpdateRateHz;
    final int outputMode;
    final long generation;
    final float[] bandEdgesHz;
    final int initialStatus;

    private SharedStreamReader(ByteBuffer memory, StreamDescriptor descriptor) {
        this.memory = memory;
        bandCount = descriptor.bandCount;
        actualAnalysisRateHz = descriptor.actualAnalysisRateHz;
        actualUpdateRateHz = descriptor.actualUpdateRateHz;
        outputMode = descriptor.outputMode;
        generation = descriptor.generation;
        bandEdgesHz = descriptor.bandEdgesHz.clone();
        initialStatus = integer(40);
    }

    /** Consumes descriptor.memory on every success or failure path. */
    static SharedStreamReader open(StreamDescriptor descriptor) {
        if (descriptor == null) throw invalid("Missing stream descriptor");
        ParcelFileDescriptor fd = descriptor.memory;
        descriptor.memory = null;
        SharedMemory shared = null;
        ByteBuffer mapped = null;
        try {
            if (fd == null) throw invalid("Missing shared memory FD");
            if (descriptor.protocolVersion != VERSION) throw new SpectrumException(
                    SpectrumException.Reason.PROTOCOL_MISMATCH, "Unsupported shared memory protocol");
            boolean waveformOnly = descriptor.outputMode == SpectrumOptions.OUTPUT_WAVEFORM;
            if (descriptor.mappedBytes != MAPPED_BYTES || descriptor.headerBytes != HEADER_BYTES
                    || descriptor.frameStride != STRIDE || descriptor.slots != SLOTS
                    || (long) HEADER_BYTES + (long) STRIDE * SLOTS > descriptor.mappedBytes
                    || descriptor.outputMode < SpectrumOptions.OUTPUT_SPECTRUM
                    || descriptor.outputMode > SpectrumOptions.OUTPUT_COMBINED
                    || descriptor.waveformBins != ReusableSpectrumFrame.WAVEFORM_BINS
                    || descriptor.actualAnalysisRateHz < 0 || descriptor.actualUpdateRateHz < 1
                    || descriptor.actualUpdateRateHz > 60 || descriptor.generation == 0
                    || (waveformOnly ? descriptor.bandCount != 0 || descriptor.actualAnalysisRateHz != 0
                        : descriptor.bandCount != 8 && descriptor.bandCount != 16 && descriptor.bandCount != 32)) {
                throw invalid("Invalid stream descriptor layout or output mode");
            }
            int edgeCount = waveformOnly ? 0 : descriptor.bandCount + 1;
            if (descriptor.bandEdgesHz == null || descriptor.bandEdgesHz.length != edgeCount) {
                throw invalid("Invalid band edges");
            }
            for (int i = 0; i < edgeCount; i++) {
                float edge = descriptor.bandEdgesHz[i];
                if (!Float.isFinite(edge) || edge < 0 || (i > 0 && edge <= descriptor.bandEdgesHz[i - 1])) {
                    throw invalid("Band edges are not finite and ascending");
                }
            }
            // Public API 33 transfers ownership by detaching the ParcelFileDescriptor.
            shared = SharedMemory.fromFileDescriptor(fd);
            if (shared.getSize() != MAPPED_BYTES) throw invalid("Backing FD size is not 8192 bytes");
            mapped = shared.mapReadOnly();
            if (mapped.capacity() != MAPPED_BYTES
                    || intAt(mapped, 0) != MAGIC || intAt(mapped, 4) != VERSION
                    || intAt(mapped, 8) != HEADER_BYTES || intAt(mapped, 12) != STRIDE
                    || intAt(mapped, 16) != SLOTS || intAt(mapped, 20) != descriptor.bandCount
                    || longAt(mapped, 32) != descriptor.generation
                    || intAt(mapped, 44) != descriptor.actualUpdateRateHz
                    || intAt(mapped, 48) != descriptor.outputMode) {
                throw invalid("Shared memory header does not match its descriptor");
            }
            int status = intAt(mapped, 40);
            if (status < SpectrumStatus.OK || status > SpectrumStatus.DROPPED) throw invalid("Invalid stream status");
            SharedStreamReader reader = new SharedStreamReader(mapped, descriptor);
            mapped = null;
            return reader;
        } catch (SpectrumException error) {
            throw error;
        } catch (Exception error) {
            throw new SpectrumException(SpectrumException.Reason.INVALID_STREAM, "Cannot map shared memory", error);
        } finally {
            if (mapped != null) SharedMemory.unmap(mapped);
            if (shared != null) shared.close();
            if (fd != null) try { fd.close(); } catch (IOException ignored) { }
        }
    }

    /** No allocation; out is unchanged until the complete spectrum AND waveform pass seqlock. */
    synchronized boolean readLatestInto(ReusableSpectrumFrame out) {
        if (memory == null) return false;
        for (int attempt = 0; attempt < 3; attempt++) {
            long sequence = (long) LONGS.getAcquire(memory, 24);
            if (sequence == 0 || longAt(memory, 32) != generation
                    || integer(20) != bandCount || integer(48) != outputMode) return false;
            if (out.streamIdentity == identity && out.sequence == sequence && out.generation == generation) return false;
            int slot = HEADER_BYTES + (int) Long.remainderUnsigned(sequence - 1, SLOTS) * STRIDE;
            long expectedVersion = sequence << 1;
            if ((long) LONGS.getAcquire(memory, slot) != expectedVersion) continue;
            long analysisTime = longAt(memory, slot + 8);
            long presentationTime = longAt(memory, slot + 16);
            long frameGeneration = longAt(memory, slot + 24);
            int status = integer(slot + 32);
            int coverage = integer(slot + 36);
            int dropped = integer(slot + 40);
            int actualRate = integer(slot + 44);
            float rms = Float.intBitsToFloat(integer(slot + 48));
            float peak = Float.intBitsToFloat(integer(slot + 52));
            int count = integer(slot + 56);
            int mode = integer(slot + 60);
            if (count != bandCount || mode != outputMode || frameGeneration != generation) continue;
            boolean valid = status >= SpectrumStatus.OK && status <= SpectrumStatus.DROPPED
                    && actualRate >= 0 && coverage >= 0 && coverage <= 1000
                    && (mode != SpectrumOptions.OUTPUT_WAVEFORM || actualRate == 0)
                    && Float.isFinite(rms) && Float.isFinite(peak);
            for (int band = 0; band < count; band++) {
                float bandDb = Float.intBitsToFloat(integer(slot + 64 + band * 4));
                spectrumScratch[band] = bandDb;
                valid &= Float.isFinite(bandDb);
            }
            int kind = integer(slot + 192);
            int waveStatus = integer(slot + 196);
            int bins = integer(slot + 200);
            int sourceRate = integer(slot + 204);
            long track = longAt(memory, slot + 208);
            long epoch = longAt(memory, slot + 216);
            long sourceSequence = longAt(memory, slot + 224);
            int windowFrames = integer(slot + 232);
            int flags = integer(slot + 236);
            long captureEnd = longAt(memory, slot + 240);
            long duration = longAt(memory, slot + 248);
            valid &= kind >= WaveformKind.NONE && kind <= WaveformKind.MIN_MAX_64
                    && waveStatus >= WaveformStatus.NONE && waveStatus <= WaveformStatus.UNSUPPORTED_PATH
                    && bins >= 0 && bins <= ReusableSpectrumFrame.WAVEFORM_BINS && sourceRate >= 0
                    && (bins == 0 || kind == WaveformKind.MIN_MAX_64)
                    && (mode != SpectrumOptions.OUTPUT_SPECTRUM
                        || kind == WaveformKind.NONE && waveStatus == WaveformStatus.NONE && bins == 0);
            Arrays.fill(waveformScratch, 0);
            if (bins > 0 && bins <= ReusableSpectrumFrame.WAVEFORM_BINS) {
                for (int bin = 0; bin < bins; bin++) {
                    float min = Float.intBitsToFloat(integer(slot + 256 + bin * 8));
                    float max = Float.intBitsToFloat(integer(slot + 260 + bin * 8));
                    waveformScratch[bin * 2] = min;
                    waveformScratch[bin * 2 + 1] = max;
                    valid &= Float.isFinite(min) && Float.isFinite(max) && min <= max;
                }
            }
            // Payload loads must precede the second observation of the slot version.
            VarHandle.acquireFence();
            if (longAt(memory, slot) != expectedVersion || longAt(memory, 32) != frameGeneration) continue;
            if (!valid) return false;
            out.streamIdentity = identity;
            out.sequence = sequence;
            out.analysisTimeNs = analysisTime;
            out.presentationTimeNs = presentationTime;
            out.generation = frameGeneration;
            out.status = status;
            out.coverage = coverage;
            out.dropped = dropped;
            out.actualAnalysisRateHz = actualRate;
            out.rmsDb = rms;
            out.peakDb = peak;
            out.bandCount = count;
            out.outputMode = mode;
            out.waveformKind = kind;
            out.waveformStatus = waveStatus;
            out.waveformBinCount = bins;
            out.waveformSourceRateHz = sourceRate;
            out.waveformTrackToken = track;
            out.waveformSourceEpoch = epoch;
            out.waveformEndSourceSequence = sourceSequence;
            out.waveformWindowFrames = windowFrames;
            out.waveformFlags = flags;
            out.waveformCaptureEndTimeNs = captureEnd;
            out.waveformWindowDurationNs = duration;
            System.arraycopy(spectrumScratch, 0, out.bands, 0, count);
            Arrays.fill(out.bands, count, out.bands.length, 0);
            System.arraycopy(waveformScratch, 0, out.waveform, 0, waveformScratch.length);
            return true;
        }
        return false;
    }

    private int integer(int offset) { return intAt(memory, offset); }
    private static int intAt(ByteBuffer buffer, int offset) { return (int) INTS.getVolatile(buffer, offset); }
    private static long longAt(ByteBuffer buffer, int offset) { return (long) LONGS.getVolatile(buffer, offset); }
    private static SpectrumException invalid(String message) {
        return new SpectrumException(SpectrumException.Reason.INVALID_STREAM, message);
    }
    @Override public synchronized void close() {
        if (memory == null) return;
        ByteBuffer old = memory;
        memory = null;
        SharedMemory.unmap(old);
    }
}
