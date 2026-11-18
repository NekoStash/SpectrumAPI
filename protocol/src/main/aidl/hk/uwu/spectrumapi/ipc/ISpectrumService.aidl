package hk.uwu.spectrumapi.ipc;

import hk.uwu.spectrumapi.ipc.Capabilities;
import hk.uwu.spectrumapi.ipc.ISpectrumSession;
import hk.uwu.spectrumapi.ipc.ISpectrumStateListener;
import hk.uwu.spectrumapi.ipc.SourceInfo;
import hk.uwu.spectrumapi.ipc.SpectrumRequest;

interface ISpectrumService {
    Capabilities getCapabilities();
    SourceInfo[] listSources();
    ISpectrumSession openSession(in SpectrumRequest request, ISpectrumStateListener listener);
}
