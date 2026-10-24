package com.termux.x11;

interface IXw11OutputBroker {
    android.os.ParcelFileDescriptor getOutputConnection(long sessionId, long surfaceGeneration);
}
