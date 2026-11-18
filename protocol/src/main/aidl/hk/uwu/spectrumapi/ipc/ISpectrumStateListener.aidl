package hk.uwu.spectrumapi.ipc;

oneway interface ISpectrumStateListener {
    // 0: OK, 1: IDLE, 2: SILENT, 3: PARTIAL, 4: UNSUPPORTED_PATH,
    // 5: UNSUPPORTED_BUILD, 6: DISCONNECTED, 7: DROPPED.
    void onStateChanged(int status, long generation);
}
