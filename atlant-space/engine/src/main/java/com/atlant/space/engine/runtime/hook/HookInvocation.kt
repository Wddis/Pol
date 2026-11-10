package com.atlant.space.engine.runtime.hook

import java.lang.reflect.Method

/** A single intercepted call. A hook may rewrite [args] in place before proceeding. */
class HookCall(val target: Any?, val method: Method, val args: Array<Any?>) {
    val name: String get() = method.name
}

/** One named interception registered against a method of a proxied interface. */
abstract class MethodHook(val methodName: String) {
    /** Return a value to short-circuit, or [PROCEED] to let the original run (possibly with rewritten args). */
    open fun before(call: HookCall): Any? = PROCEED

    /** Observe or replace the original's result. */
    open fun after(call: HookCall, result: Any?): Any? = result

    companion object {
        val PROCEED = Any()
    }
}
