package com.atlant.space.engine.runtime.hook

import com.atlant.space.engine.runtime.VirtualRuntime
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Wraps a framework singleton (an IActivityManager / IPackageManager / ... Binder proxy) in a
 * dynamic proxy and routes every method through the registered [MethodHook]s. Unhooked methods
 * are forwarded unchanged, so an interface we only partly understand keeps working across
 * Android versions.
 */
class ProxyInvocationHandler(val base: Any) : InvocationHandler {
    private val hooks = HashMap<String, MutableList<MethodHook>>()

    fun add(hook: MethodHook) = apply { hooks.getOrPut(hook.methodName) { ArrayList() }.add(hook) }

    override fun invoke(proxy: Any, method: Method, rawArgs: Array<Any?>?): Any? {
        val args = rawArgs ?: emptyArray()
        if (method.declaringClass == Any::class.java) return method.invoke(base, *args)
        val list = hooks[method.name]
        if (list == null) return call(method, args)
        val hc = HookCall(base, method, args)
        for (h in list) {
            val pre = try { h.before(hc) } catch (t: Throwable) { logHookError(h, t); MethodHook.PROCEED }
            if (pre !== MethodHook.PROCEED) return pre
        }
        var result = call(method, hc.args)
        for (h in list) result = try { h.after(hc, result) } catch (t: Throwable) { logHookError(h, t); result }
        return result
    }

    private fun call(method: Method, args: Array<Any?>): Any? = try {
        method.invoke(base, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

    private fun logHookError(h: MethodHook, t: Throwable) {
        android.util.Log.w("Atlant/Hook", "Hook ${h.methodName} failed: ${t.javaClass.simpleName}: ${t.message}")
    }

    companion object {
        fun wrap(iface: Class<*>, base: Any, configure: (ProxyInvocationHandler) -> Unit): Any {
            val handler = ProxyInvocationHandler(base).also(configure)
            return Proxy.newProxyInstance(iface.classLoader, collectInterfaces(base.javaClass, iface), handler)
        }

        private fun collectInterfaces(cls: Class<*>, primary: Class<*>): Array<Class<*>> {
            val set = LinkedHashSet<Class<*>>()
            set.add(primary)
            var c: Class<*>? = cls
            while (c != null) { set.addAll(c.interfaces); c = c.superclass }
            return set.toTypedArray()
        }
    }
}
