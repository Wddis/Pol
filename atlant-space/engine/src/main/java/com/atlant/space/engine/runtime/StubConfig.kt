package com.atlant.space.engine.runtime

/**
 * Names and counts of the proxy ("stub") components the host declares in its own manifest.
 *
 * Android only ever launches components that exist in an installed package's manifest. A guest
 * app's components are not in any installed manifest, so the engine cannot ask the system to
 * launch them directly. Instead the host declares a fixed pool of placeholder components; the
 * hook layer asks the system to launch a placeholder, then substitutes the real guest component
 * inside the receiving process. The pool is sized per guest process so each guest process maps
 * to its own host process (`:vproc0`, `:vproc1`, ...).
 */
object StubConfig {
    /** Number of guest processes that can run at once (one host process each). */
    const val PROCESS_SLOTS = 6

    /** Stub activities per process; a task stack rarely needs more live records than this. */
    const val STUB_ACTIVITIES_PER_PROCESS = 8

    const val AUTHORITY_PROXY = "com.atlant.space.proxy"
    const val AUTHORITY_SETTINGS = "com.atlant.space.settings"

    fun stubProcessName(hostPackage: String, slot: Int) = "$hostPackage:vproc$slot"

    fun stubActivity(slot: Int, index: Int) = "com.atlant.space.runtime.stub.StubActivity\$P$slot\$A$index"

    fun stubService(slot: Int) = "com.atlant.space.runtime.stub.StubService\$P$slot"

    fun stubProvider(slot: Int) = "com.atlant.space.runtime.stub.StubProvider\$P$slot"

    /** True for a component class name that belongs to the stub pool. */
    fun isStub(className: String?) = className != null && className.startsWith("com.atlant.space.runtime.stub.")
}
