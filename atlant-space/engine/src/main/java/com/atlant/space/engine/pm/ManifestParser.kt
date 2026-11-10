package com.atlant.space.engine.pm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** One `<intent-filter>` of a component. */
data class ParsedFilter(
    val actions: List<String>,
    val categories: List<String>,
    val schemes: List<String>,
    /** host to port ("" = any port). */
    val authorities: List<Pair<String, String>>,
    /** path to PatternMatcher type (0 literal, 1 prefix, 2 simple glob). */
    val paths: List<Pair<String, Int>>,
    val mimeTypes: List<String>,
    val priority: Int,
) {
    fun toJson() = JSONObject().apply {
        put("a", JSONArray(actions)); put("c", JSONArray(categories)); put("s", JSONArray(schemes))
        put("h", JSONArray(authorities.map { JSONArray(listOf(it.first, it.second)) }))
        put("p", JSONArray(paths.map { JSONArray(listOf(it.first, it.second)) }))
        put("m", JSONArray(mimeTypes)); put("pr", priority)
    }

    companion object {
        fun fromJson(o: JSONObject) = ParsedFilter(
            o.getJSONArray("a").strings(), o.getJSONArray("c").strings(), o.getJSONArray("s").strings(),
            o.getJSONArray("h").arrays().map { it.getString(0) to it.getString(1) },
            o.getJSONArray("p").arrays().map { it.getString(0) to it.getInt(1) },
            o.getJSONArray("m").strings(), o.optInt("pr"),
        )
    }
}

enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

data class ParsedComponent(
    val type: ComponentType,
    /** Fully qualified class name. */
    val name: String,
    /** For `<activity-alias>`: the real activity class. */
    val target: String?,
    /** Process name as declared (may start with ':'), or null for the default process. */
    val process: String?,
    /** Null when the manifest does not say; the platform default then depends on filters. */
    val exported: Boolean?,
    val enabled: Boolean,
    val authorities: List<String>,
    val filters: List<ParsedFilter>,
) {
    val isExported: Boolean get() = exported ?: filters.isNotEmpty()

    fun toJson() = JSONObject().apply {
        put("t", type.ordinal); put("n", name)
        if (target != null) put("tg", target)
        if (process != null) put("p", process)
        if (exported != null) put("x", exported)
        put("e", enabled); put("au", JSONArray(authorities)); put("f", JSONArray(filters.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject) = ParsedComponent(
            ComponentType.entries[o.getInt("t")], o.getString("n"),
            if (o.has("tg")) o.getString("tg") else null,
            if (o.has("p")) o.getString("p") else null,
            if (o.has("x")) o.getBoolean("x") else null,
            o.optBoolean("e", true), o.getJSONArray("au").strings(),
            o.getJSONArray("f").objects().map { ParsedFilter.fromJson(it) },
        )
    }
}

data class ParsedManifest(
    val packageName: String,
    /** Non-null for a split APK. */
    val split: String?,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val applicationClass: String?,
    val applicationProcess: String?,
    val hasCode: Boolean,
    val permissions: List<String>,
    val usesLibraries: List<String>,
    val metaDataKeys: List<String>,
    val components: List<ParsedComponent>,
) {
    fun processOf(c: ParsedComponent): String = resolveProcess(c.process ?: applicationProcess)

    fun resolveProcess(declared: String?): String = when {
        declared.isNullOrEmpty() -> packageName
        declared.startsWith(":") -> packageName + declared
        else -> declared
    }

    fun component(className: String): ParsedComponent? = components.firstOrNull { it.name == className }

    fun launcher(): ParsedComponent? = components.firstOrNull { c ->
        c.type == ComponentType.ACTIVITY && c.enabled && c.filters.any {
            "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories
        }
    }

    fun toJson() = JSONObject().apply {
        put("pkg", packageName); if (split != null) put("split", split)
        put("vc", versionCode); put("vn", versionName); put("min", minSdk); put("tgt", targetSdk)
        if (applicationClass != null) put("app", applicationClass)
        if (applicationProcess != null) put("proc", applicationProcess)
        put("code", hasCode); put("perm", JSONArray(permissions)); put("libs", JSONArray(usesLibraries))
        put("meta", JSONArray(metaDataKeys)); put("comp", JSONArray(components.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject) = ParsedManifest(
            o.getString("pkg"), if (o.has("split")) o.getString("split") else null,
            o.optLong("vc"), o.optString("vn"), o.optInt("min", 1), o.optInt("tgt", 1),
            if (o.has("app")) o.getString("app") else null, if (o.has("proc")) o.getString("proc") else null,
            o.optBoolean("code", true), o.getJSONArray("perm").strings(), o.getJSONArray("libs").strings(),
            o.getJSONArray("meta").strings(), o.getJSONArray("comp").objects().map { ParsedComponent.fromJson(it) },
        )
    }
}

internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
internal fun JSONArray.arrays(): List<JSONArray> = List(length()) { getJSONArray(it) }

/**
 * Reader for the compiled (binary) AndroidManifest.xml of an APK.
 *
 * The platform's own parser is used for everything it exposes publicly (ActivityInfo and
 * friends); this one supplies what the public API withholds for archive files — intent
 * filters and split metadata. It is plain Kotlin with no Android dependency.
 */
object ManifestParser {
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_RES_MAP = 0x0180
    private const val CHUNK_START_TAG = 0x0102
    private const val CHUNK_END_TAG = 0x0103

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOL = 0x12

    private const val A_NAME = 0x01010003
    private const val A_PROCESS = 0x01010011
    private const val A_EXPORTED = 0x01010010
    private const val A_ENABLED = 0x0101000e
    private const val A_VERSION_CODE = 0x0101021b
    private const val A_VERSION_CODE_MAJOR = 0x01010576
    private const val A_VERSION_NAME = 0x0101021c
    private const val A_MIN_SDK = 0x0101020c
    private const val A_TARGET_SDK = 0x01010270
    private const val A_SCHEME = 0x01010027
    private const val A_HOST = 0x01010028
    private const val A_PORT = 0x01010029
    private const val A_PATH = 0x0101002a
    private const val A_PATH_PREFIX = 0x0101002b
    private const val A_PATH_PATTERN = 0x0101002c
    private const val A_MIME_TYPE = 0x01010026
    private const val A_PRIORITY = 0x0101001c
    private const val A_TARGET_ACTIVITY = 0x01010202
    private const val A_AUTHORITIES = 0x01010018
    private const val A_HAS_CODE = 0x0101000c

    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024

    private class Attr(val name: String, val resId: Int, val type: Int, val data: Int, val raw: String?)

    private class Tag(val name: String, val attrs: List<Attr>) {
        fun attr(resId: Int, plainName: String? = null): Attr? =
            attrs.firstOrNull { it.resId == resId } ?: plainName?.let { n -> attrs.firstOrNull { it.resId == 0 && it.name == n } }

        fun str(resId: Int, plainName: String? = null): String? {
            val a = attr(resId, plainName) ?: return null
            return when (a.type) {
                TYPE_STRING -> a.raw
                TYPE_INT_DEC -> a.data.toString()
                else -> a.raw?.takeIf { a.type != TYPE_REFERENCE }
            }
        }

        fun int(resId: Int): Int? {
            val a = attr(resId) ?: return null
            return when (a.type) {
                TYPE_INT_DEC, TYPE_INT_HEX, TYPE_INT_BOOL -> a.data
                TYPE_STRING -> a.raw?.toIntOrNull()
                else -> null
            }
        }

        fun bool(resId: Int): Boolean? {
            val a = attr(resId) ?: return null
            return when (a.type) {
                TYPE_INT_BOOL, TYPE_INT_DEC, TYPE_INT_HEX -> a.data != 0
                TYPE_STRING -> a.raw == "true"
                else -> null
            }
        }
    }

    @Throws(IOException::class)
    fun parseApk(apk: File): ParsedManifest {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: throw IOException("No AndroidManifest.xml in ${apk.name}")
            if (entry.size > MAX_MANIFEST_BYTES) throw IOException("Manifest is unreasonably large")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return parse(bytes)
        }
    }

    @Throws(IOException::class)
    fun parse(bytes: ByteArray): ParsedManifest = try {
        build(readTags(bytes))
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        // Truncated or hostile input must surface as a normal failure, not as a crash.
        throw IOException("Malformed manifest: ${e.javaClass.simpleName}", e)
    }

    /** Flattens the document into (depth, tag) pairs in document order. */
    private fun readTags(bytes: ByteArray): List<Pair<Int, Tag>> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || (buf.getShort(0).toInt() and 0xffff) != CHUNK_XML) throw IOException("Not a binary XML manifest")
        var strings: Array<String?> = emptyArray()
        var resMap = IntArray(0)
        val out = ArrayList<Pair<Int, Tag>>()
        var depth = 0
        var pos = buf.getShort(2).toInt() and 0xffff
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xffff
            val headerSize = buf.getShort(pos + 2).toInt() and 0xffff
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) throw IOException("Corrupt chunk at $pos")
            when (type) {
                CHUNK_STRING_POOL -> strings = readStringPool(buf, pos)
                CHUNK_RES_MAP -> resMap = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                CHUNK_START_TAG -> {
                    val body = pos + headerSize
                    val name = strings.getOrNull(buf.getInt(body + 4)) ?: ""
                    val attrStart = buf.getShort(body + 8).toInt() and 0xffff
                    val attrSize = (buf.getShort(body + 10).toInt() and 0xffff).coerceAtLeast(20)
                    val attrCount = buf.getShort(body + 12).toInt() and 0xffff
                    val attrs = ArrayList<Attr>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = body + attrStart + i * attrSize
                        val nameIdx = buf.getInt(a + 4)
                        val rawIdx = buf.getInt(a + 8)
                        val dataType = buf.get(a + 15).toInt() and 0xff
                        val data = buf.getInt(a + 16)
                        val raw = when {
                            dataType == TYPE_STRING -> strings.getOrNull(data)
                            rawIdx >= 0 -> strings.getOrNull(rawIdx)
                            else -> null
                        }
                        attrs.add(Attr(strings.getOrNull(nameIdx) ?: "", resMap.getOrElse(nameIdx) { 0 }, dataType, data, raw))
                    }
                    out.add(depth to Tag(name, attrs))
                    depth++
                }
                CHUNK_END_TAG -> depth--
            }
            pos += size
        }
        return out
    }

    private fun readStringPool(buf: ByteBuffer, start: Int): Array<String?> {
        val headerSize = buf.getShort(start + 2).toInt() and 0xffff
        val chunkSize = buf.getInt(start + 4)
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        val utf8 = flags and (1 shl 8) != 0
        if (count < 0 || count > chunkSize / 4) throw IOException("Corrupt string pool")
        val end = start + chunkSize
        return Array(count) { i ->
            val off = start + stringsStart + buf.getInt(start + headerSize + i * 4)
            if (off < start || off >= end) null else runCatching { readString(buf, off, end, utf8) }.getOrNull()
        }
    }

    private fun readString(buf: ByteBuffer, offset: Int, end: Int, utf8: Boolean): String {
        var p = offset
        if (utf8) {
            // Two varints: length in UTF-16 units (unused) and length in bytes.
            if (buf.get(p).toInt() and 0x80 != 0) p += 2 else p += 1
            var len = buf.get(p).toInt() and 0xff
            if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (buf.get(p + 1).toInt() and 0xff); p += 2 } else p += 1
            if (p + len > end) throw IOException("String overruns pool")
            val b = ByteArray(len)
            for (i in 0 until len) b[i] = buf.get(p + i)
            return String(b, Charsets.UTF_8)
        }
        var len = buf.getShort(p).toInt() and 0xffff
        p += 2
        if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (buf.getShort(p).toInt() and 0xffff); p += 2 }
        if (p + len * 2 > end) throw IOException("String overruns pool")
        val chars = CharArray(len) { buf.getShort(p + it * 2).toInt().toChar() }
        return String(chars)
    }

    private fun build(tags: List<Pair<Int, Tag>>): ParsedManifest {
        val root = tags.firstOrNull { it.first == 0 && it.second.name == "manifest" }?.second
            ?: throw IOException("No <manifest> element")
        val pkg = root.str(0, "package")?.takeIf { it.isNotEmpty() } ?: throw IOException("Manifest has no package name")
        val split = root.str(0, "split")?.takeIf { it.isNotEmpty() }
        val versionCode = ((root.int(A_VERSION_CODE_MAJOR) ?: 0).toLong() shl 32) or
            ((root.int(A_VERSION_CODE) ?: 0).toLong() and 0xffffffffL)
        var minSdk = 1
        var targetSdk = -1
        var appClass: String? = null
        var appProcess: String? = null
        var hasCode = true
        val permissions = LinkedHashSet<String>()
        val libraries = ArrayList<String>()
        val metaKeys = ArrayList<String>()
        val components = ArrayList<ParsedComponent>()

        fun qualify(name: String): String = when {
            name.startsWith(".") -> pkg + name
            '.' !in name -> "$pkg.$name"
            else -> name
        }

        var i = 0
        while (i < tags.size) {
            val (depth, tag) = tags[i]
            when {
                depth == 1 && tag.name == "uses-sdk" -> {
                    minSdk = tag.int(A_MIN_SDK) ?: 1
                    targetSdk = tag.int(A_TARGET_SDK) ?: -1
                }
                depth == 1 && (tag.name == "uses-permission" || tag.name == "uses-permission-sdk-23") ->
                    tag.str(A_NAME)?.let { permissions.add(it) }
                depth == 1 && tag.name == "application" -> {
                    appClass = tag.str(A_NAME)?.let(::qualify)
                    appProcess = tag.str(A_PROCESS)
                    hasCode = tag.bool(A_HAS_CODE) ?: true
                }
                depth == 2 && tag.name == "uses-library" -> tag.str(A_NAME)?.let { libraries.add(it) }
                depth == 2 && tag.name == "meta-data" -> tag.str(A_NAME)?.let { metaKeys.add(it) }
                depth == 2 && tag.name in COMPONENT_TAGS -> {
                    val name = tag.str(A_NAME)
                    val filters = ArrayList<ParsedFilter>()
                    var j = i + 1
                    while (j < tags.size && tags[j].first > 2) {
                        if (tags[j].first == 3 && tags[j].second.name == "intent-filter") {
                            val (filter, next) = readFilter(tags, j)
                            filters.add(filter)
                            j = next
                        } else j++
                    }
                    if (name != null) {
                        components.add(
                            ParsedComponent(
                                COMPONENT_TAGS.getValue(tag.name), qualify(name),
                                tag.str(A_TARGET_ACTIVITY)?.let(::qualify), tag.str(A_PROCESS),
                                tag.bool(A_EXPORTED), tag.bool(A_ENABLED) ?: true,
                                tag.str(A_AUTHORITIES)?.split(';')?.filter { it.isNotEmpty() }.orEmpty(), filters,
                            ),
                        )
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        return ParsedManifest(
            pkg, split, versionCode, root.str(A_VERSION_NAME) ?: "", minSdk, if (targetSdk < 0) minSdk else targetSdk,
            appClass, appProcess, hasCode, permissions.toList(), libraries, metaKeys, components,
        )
    }

    private fun readFilter(tags: List<Pair<Int, Tag>>, start: Int): Pair<ParsedFilter, Int> {
        val actions = ArrayList<String>()
        val categories = ArrayList<String>()
        val schemes = ArrayList<String>()
        val hosts = ArrayList<Pair<String, String>>()
        val paths = ArrayList<Pair<String, Int>>()
        val mimes = ArrayList<String>()
        val priority = tags[start].second.int(A_PRIORITY) ?: 0
        var j = start + 1
        while (j < tags.size && tags[j].first > 3) {
            val t = tags[j].second
            when (t.name) {
                "action" -> t.str(A_NAME)?.let { actions.add(it) }
                "category" -> t.str(A_NAME)?.let { categories.add(it) }
                "data" -> {
                    t.str(A_SCHEME)?.let { schemes.add(it) }
                    t.str(A_HOST)?.let { hosts.add(it to (t.str(A_PORT) ?: "")) }
                    t.str(A_PATH)?.let { paths.add(it to 0) }
                    t.str(A_PATH_PREFIX)?.let { paths.add(it to 1) }
                    t.str(A_PATH_PATTERN)?.let { paths.add(it to 2) }
                    t.str(A_MIME_TYPE)?.let { mimes.add(it) }
                }
            }
            j++
        }
        return ParsedFilter(actions, categories, schemes.distinct(), hosts, paths, mimes, priority) to j
    }

    private val COMPONENT_TAGS = mapOf(
        "activity" to ComponentType.ACTIVITY, "activity-alias" to ComponentType.ACTIVITY,
        "service" to ComponentType.SERVICE, "receiver" to ComponentType.RECEIVER, "provider" to ComponentType.PROVIDER,
    )
}
