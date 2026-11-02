package dev.matryoshka.vm.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.matryoshka.vm.core.guest.ApkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class UriInfo(val name: String, val size: Long)

fun Context.describe(uri: Uri): UriInfo {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val name = c.getString(0) ?: uri.lastPathSegment ?: "file"
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            return UriInfo(name, size)
        }
    }
    return UriInfo(uri.lastPathSegment ?: "file", -1L)
}

/**
 * `pm install -S` needs the exact size up front. When the provider does not report it, the
 * file is staged in the cache first.
 */
suspend fun Context.apkSourceOf(uri: Uri): ApkSource = withContext(Dispatchers.IO) {
    val info = describe(uri)
    if (info.size > 0) return@withContext ApkSource(info.name, info.size) { contentResolver.openInputStream(uri)!! }
    val staged = File(cacheDir, "staged-${System.nanoTime()}.apk")
    contentResolver.openInputStream(uri)!!.use { input -> staged.outputStream().use { input.copyTo(it) } }
    staged.deleteOnExit()
    ApkSource(info.name, staged.length()) { staged.inputStream() }
}
