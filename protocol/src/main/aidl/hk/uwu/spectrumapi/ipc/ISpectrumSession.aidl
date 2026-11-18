package hk.uwu.spectrumapi.ipc;

import hk.uwu.spectrumapi.ipc.SpectrumRequest;
import hk.uwu.spectrumapi.ipc.StreamDescriptor;

interface ISpectrumSession {
    StreamDescriptor getStream();
    void update(in SpectrumRequest request);
    void setActive(boolean active);
    void close();
}
