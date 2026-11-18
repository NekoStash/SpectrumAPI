package hk.uwu.spectrumapi.ipc;

parcelable SourceInfo {
    long token = 0;
    int uid = -1;
    int sessionId = 0;
    int usage = 0;
    int sampleRate = 0;
    int format = 0;
    int channels = 0;
    boolean active = false;
    int mode = 0;
    String label = "";
}
