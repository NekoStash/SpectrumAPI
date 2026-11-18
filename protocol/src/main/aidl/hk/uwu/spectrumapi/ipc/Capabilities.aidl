package hk.uwu.spectrumapi.ipc;

parcelable Capabilities {
    int protocolVersion = 1;
    boolean captureAvailable = false;
    String diagnostic = "";
    int maxUpdateRateHz = 60;
    int maxAnalysisRateHz = 192000;
    int maxBandCount = 32;
    int maxActiveSources = 8;
    int supportedModes = 0;
    // Bit 0: SPECTRUM, bit 1: WAVEFORM, bit 2: COMBINED.
    int supportedOutputModes = 7;
    int waveformBinCount = 64;
}
