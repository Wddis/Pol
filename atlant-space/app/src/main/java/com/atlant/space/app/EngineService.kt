package com.atlant.space.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.atlant.space.engine.AtlantEngine

/**
 * Hosts the engine binder. A guest process binds to this service to obtain the [AtlantEngine]
 * channel back to the main process (permission checks, the shared journal, its space's Android ID,
 * virtual-root state). The service holds no state of its own; it just returns the engine's binder.
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder = AtlantEngine.get(this).service
}
