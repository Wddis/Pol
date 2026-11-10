package com.atlant.space.engine.ipc;

import com.atlant.space.engine.ipc.IProcessObserver;

/** Binder served by the main process to every guest process. */
interface IAtlantEngine {
    /** A guest process registers itself once its runtime is attached. */
    void onProcessAttached(int spaceId, String processName, int pid, IBinder token);

    /** A guest asks whether a runtime permission is granted (virtual permission layer). */
    boolean isPermissionGranted(int spaceId, String pkg, String permission, boolean requested);

    /** Records a line in the shared event journal. */
    void reportEvent(int spaceId, String pkg, int level, String tag, String message);

    /** The virtual Android ID of a space (16 hex). */
    String androidId(int spaceId);

    void registerProcessObserver(IProcessObserver observer);
    void unregisterProcessObserver(IProcessObserver observer);

    /** Virtual root: whether [pkg] in [spaceId] currently holds virtual superuser. */
    boolean hasVirtualRoot(int spaceId, String pkg);
}
