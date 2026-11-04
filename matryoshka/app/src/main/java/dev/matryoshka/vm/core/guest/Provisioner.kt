package dev.matryoshka.vm.core.guest

import dev.matryoshka.vm.core.image.BootImage
import dev.matryoshka.vm.core.host.shQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional third-party components installed *into the guest* at the user's request.
 * Nothing here is bundled in the Matryoshka APK: each component is downloaded from its own
 * official release page, keeping licences separate (Magisk: GPL-3.0, microG: Apache-2.0).
 */
data class Component(
    val id: String,
    val title: String,
    val license: String,
    val githubRepo: String,
    val assetPattern: Regex,
    val homepage: String,
    /** Fixed download URL instead of a GitHub release (the project's own site). */
    val directUrl: String? = null,
)

object Components {
    val MAGISK = Component(
        id = "magisk", title = "Magisk", license = "GPL-3.0",
        githubRepo = "topjohnwu/Magisk", assetPattern = Regex("""^Magisk-v[\d.]+\.apk$"""),
        homepage = "https://github.com/topjohnwu/Magisk",
    )
    val MICROG_CORE = Component(
        id = "microg-core", title = "microG Services", license = "Apache-2.0",
        githubRepo = "microg/GmsCore", assetPattern = Regex("""^com\.google\.android\.gms-\d+\.apk$"""),
        homepage = "https://microg.org",
    )
    /** F-Droid, the free-software app store (its own signed build from f-droid.org). */
    val FDROID = Component(
        id = "fdroid", title = "F-Droid", license = "GPL-3.0",
        githubRepo = "", assetPattern = Regex("""^F-Droid\.apk$"""),
        homepage = "https://f-droid.org", directUrl = "https://f-droid.org/F-Droid.apk",
    )
    val MICROG_COMPANION = Component(
        id = "microg-companion", title = "microG Companion", license = "Apache-2.0",
        githubRepo = "microg/GmsCore", assetPattern = Regex("""^com\.android\.vending-\d+\.apk$"""),
        homepage = "https://microg.org",
    )
}

class Provisioner(private val cacheDir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Asset(val name: String, val browser_download_url: String, val size: Long = 0)
    @Serializable private data class Release(val tag_name: String, val assets: List<Asset> = emptyList())

    /** Downloads the newest release asset of [c]; cached by file name. */
    suspend fun fetch(c: Component, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        c.directUrl?.let { url ->
            val target = File(cacheDir, "components/${c.id}.apk").apply { parentFile?.mkdirs() }
            val part = File(target.path + ".part")
            http(url).inputStream.use { input -> part.outputStream().use { input.copyTo(it, 128 * 1024) } }
            if (!part.renameTo(target)) throw IOException("Could not store ${c.title}")
            return@withContext target
        }
        val release = http("https://api.github.com/repos/${c.githubRepo}/releases/latest").inputStream.use {
            json.decodeFromString(Release.serializer(), it.readBytes().decodeToString())
        }
        val asset = release.assets.firstOrNull { c.assetPattern.matches(it.name) }
            ?: throw IOException("No matching ${c.title} asset in ${release.tag_name}")
        val target = File(cacheDir, "components/${asset.name}").apply { parentFile?.mkdirs() }
        if (target.exists() && (asset.size == 0L || target.length() == asset.size)) return@withContext target
        val part = File(target.path + ".part")
        http(asset.browser_download_url).inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (asset.size > 0) onProgress(done.toFloat() / asset.size)
                }
            }
        }
        part.renameTo(target)
        target
    }

    /**
     * microG needs signature spoofing, which the Matryoshka guest image enables for
     * `com.google.android.gms` only (see guest/patches/).
     */
    suspend fun installMicroG(guest: GuestClient, log: (String) -> Unit) {
        for (c in listOf(Components.MICROG_CORE, Components.MICROG_COMPANION)) {
            log("Downloading ${c.title}…")
            val apk = fetch(c)
            log("Installing ${c.title}…")
            guest.install(listOf(ApkSource(apk.name, apk.length()) { apk.inputStream() }))
        }
        guest.shell("pm grant com.google.android.gms android.permission.FAKE_PACKAGE_SIGNATURE 2>/dev/null; true")
        val spoof = guest.shell("dumpsys package com.google.android.gms | grep -c FAKE_PACKAGE_SIGNATURE").second.trim()
        log(if (spoof != "0") "microG installed. Open “microG Settings” in the virtual device to finish setup."
            else "microG installed, but this guest image has no signature spoofing — some apps will not work.")
    }

    /** Installs a plain app component (an app store) into the guest. */
    suspend fun installApp(guest: GuestClient, c: Component, log: (String) -> Unit) {
        log("Downloading ${c.title}…")
        val apk = fetch(c)
        log("Installing ${c.title}…")
        guest.install(listOf(ApkSource(apk.name, apk.length()) { apk.inputStream() }))
        log("${c.title} installed")
    }

    /**
     * Roots the guest with Magisk using Magisk's own `boot_patch.sh`, executed *inside* the guest:
     * the stock guest boot.img is patched there, then the patched ramdisk/kernel are pulled back
     * and used by the engine from the next boot on. Matches what the Magisk app does for
     * "Select and patch a file", just automated.
     */
    suspend fun installMagisk(guest: GuestClient, stockBootImg: File, outDir: File, log: (String) -> Unit) {
        if (!stockBootImg.exists()) throw IOException("This image has no boot.img, Magisk cannot be installed")
        log("Downloading Magisk…")
        val apk = fetch(Components.MAGISK)
        val d = "/data/local/tmp/mvm-magisk"
        guest.shell("rm -rf $d; mkdir -p $d")
        log("Copying files into the virtual device…")
        apk.inputStream().use { guest.push(it, "$d/magisk.apk") }
        stockBootImg.inputStream().use { guest.push(it, "$d/boot.img") }
        log("Patching boot image with Magisk…")
        val prep = """
            cd $d && unzip -o magisk.apk 'assets/*' 'lib/arm64-v8a/*' >/dev/null &&
            for f in lib/arm64-v8a/lib*.so; do n=${'$'}{f##*/lib}; n=${'$'}{n%.so}; cp "${'$'}f" "./${'$'}n"; done &&
            cp -r assets/* . && chmod -R 755 $d
        """.trimIndent().replace("\n", " ")
        val (pc, po) = guest.shell(prep)
        if (pc != 0) throw IOException("Could not unpack Magisk: $po")
        val patch = "cd $d && KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false ./busybox sh boot_patch.sh $d/boot.img"
        val (code, out) = guest.shell("su 0 sh -c ${patch.shQuote()} || su -c ${patch.shQuote()}")
        if (code != 0 || guest.shell("test -f $d/new-boot.img").first != 0) {
            throw IOException("Magisk patch failed:\n${out.takeLast(1500)}")
        }
        outDir.mkdirs()
        val patched = File(outDir, "boot.magisk.img")
        patched.outputStream().use { guest.pull("$d/new-boot.img", it) }
        BootImage.extract(patched, File(outDir, "kernel.magisk"), File(outDir, "ramdisk.magisk.img"))
        log("Installing Magisk app…")
        guest.install(listOf(ApkSource(apk.name, apk.length()) { apk.inputStream() }))
        guest.shell("rm -rf $d")
        log("Magisk is ready. Restart the virtual device to activate root.")
    }

    private fun http(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Matryoshka-VM")
            c.setRequestProperty("Accept", "application/vnd.github+json, */*")
            when (c.responseCode) {
                in 300..399 -> { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
                in 200..299 -> return c
                else -> throw IOException("HTTP ${c.responseCode} for $u")
            }
        }
        throw IOException("Too many redirects")
    }
}
