package com.atlant.space.engine.gms

import android.content.Context

/**
 * Google Mobile Services compatibility.
 *
 * Many apps link Google Play Services and degrade gracefully when it is absent, but some call it
 * eagerly on startup and crash if the `com.google.android.gms` package cannot be resolved. ATLANT
 * SPACE does not bundle GMS (that would blow the size budget and raises licensing questions);
 * instead it detects whether the *host device* has Play Services and, when a guest declares a
 * dependency on it, lets the guest see and bind to the real host GMS through the package-manager
 * hook rather than pretending GMS exists. When the host has no GMS, the guest is told so honestly
 * so it can take its no-Play-Services path instead of hanging on a bind that never completes.
 */
class GmsSupport(private val context: Context) {

    /** True when Google Play Services is installed on the host and can back a guest's calls. */
    val hostHasGms: Boolean by lazy {
        runCatching {
            context.packageManager.getPackageInfo(GMS_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** Packages a guest may resolve through to the host when it depends on Play Services. */
    val passthroughPackages: Set<String> = setOf(GMS_PACKAGE, GSF_PACKAGE, VENDING_PACKAGE)

    /**
     * Decides what to do for a guest that declares a GMS dependency.
     *  - [Mode.PASSTHROUGH] when the host has GMS: the guest binds to the real service.
     *  - [Mode.ABSENT] otherwise: the guest resolves GMS as not installed and takes its fallback.
     */
    fun modeFor(usesGms: Boolean): Mode = when {
        !usesGms -> Mode.NOT_NEEDED
        hostHasGms -> Mode.PASSTHROUGH
        else -> Mode.ABSENT
    }

    enum class Mode { NOT_NEEDED, PASSTHROUGH, ABSENT }

    companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val GSF_PACKAGE = "com.google.android.gsf"
        const val VENDING_PACKAGE = "com.android.vending"
    }
}
