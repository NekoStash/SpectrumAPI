package hk.uwu.spectrumapi.ipc;

parcelable SpectrumRequest {
    // 0: APP_UID, 1: TRACK, 2: ALL_MEDIA, 3: OUTPUT_MIX.
    int sourceMode = 0;
    int targetUid = -1;
    long trackToken = 0;
    long outputToken = 0;
    int usage = 1;
    int analysisRateHz = 24000;
    // 0: AUTO, 1: SOURCE, 2: EXACT, 3: AT_MOST.
    int analysisRatePolicy = 0;
    int updateRateHz = 30;
    int bandCount = 16;
    float minFrequencyHz = 40.0f;
    float maxFrequencyHz = 8000.0f;
    // 0: SPECTRUM, 1: WAVEFORM, 2: COMBINED.
    int outputMode = 0;
}
