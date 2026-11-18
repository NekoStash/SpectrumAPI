package hk.uwu.spectrumapi.ipc;

import android.os.ParcelFileDescriptor;

parcelable StreamDescriptor {
    ParcelFileDescriptor memory;
    int protocolVersion = 1;
    int mappedBytes = 8192;
    int headerBytes = 64;
    int frameStride = 768;
    int slots = 8;
    int bandCount = 16;
    int actualAnalysisRateHz = 0;
    int actualUpdateRateHz = 30;
    long generation = 1;
    float[] bandEdgesHz;
    int outputMode = 0;
    int waveformBins = 64;
}
