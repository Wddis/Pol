package com.atlant.space.engine.util

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Small reflection helper used by the hook layer. It caches lookups and walks the class
 * hierarchy, so a field declared on a superclass is still found. Every access goes through
 * here so that a failure on an unexpected OEM/version shape is reported with context rather
 * than a bare NoSuchFieldException deep in the engine.
 */
object Reflect {
    private val fields = HashMap<String, Field>()
    private val methods = HashMap<String, Method>()

    fun field(cls: Class<*>, name: String): Field = fields.getOrPut("${cls.name}#$name") {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return@getOrPut c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException("${cls.name}.$name")
    }

    fun get(target: Any, name: String): Any? = field(target.javaClass, name).get(target)
    fun getStatic(cls: Class<*>, name: String): Any? = field(cls, name).get(null)
    fun set(target: Any, name: String, value: Any?) = field(target.javaClass, name).set(target, value)
    fun setStatic(cls: Class<*>, name: String, value: Any?) = field(cls, name).set(null, value)

    fun method(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        val key = "${cls.name}#$name(${paramTypes.joinToString { it.name }})"
        return methods.getOrPut(key) {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    return@getOrPut c.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    c = c.superclass
                }
            }
            throw NoSuchMethodException(key)
        }
    }

    fun call(target: Any, name: String, args: List<Pair<Class<*>, Any?>> = emptyList()): Any? =
        method(target.javaClass, name, *args.map { it.first }.toTypedArray()).invoke(target, *args.map { it.second }.toTypedArray())

    fun callStatic(cls: Class<*>, name: String, args: List<Pair<Class<*>, Any?>> = emptyList()): Any? =
        method(cls, name, *args.map { it.first }.toTypedArray()).invoke(null, *args.map { it.second }.toTypedArray())

    inline fun <T> quietly(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
}
