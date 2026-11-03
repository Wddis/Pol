// SPDX-License-Identifier: Apache-2.0
package dev.matryoshka.vm.core.nest

import android.view.Surface

/** JNI side of the guest display (nest/src/view_jni.cpp): frames copied straight into a Surface. */
object NestNativeView {
    val available: Boolean = runCatching { System.loadLibrary("nest_view") }.isSuccess

    /** Starts copying the engine's frames (STATE/display.sock) into [surface]; 0 if impossible. */
    @JvmStatic external fun start(surface: Surface, socketPath: String): Long
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun frames(handle: Long): Long
}
