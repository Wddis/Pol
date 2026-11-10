package com.atlant.space.engine.ipc;

interface IProcessObserver {
    void onProcessStarted(int spaceId, String processName, int pid);
    void onProcessDied(int spaceId, String processName, int pid);
}
