package com.atlant.space.engine.util

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** File helpers shared by the engine. Every operation either succeeds or throws. */
object Files {

    /** Writes [text] atomically: a crash can leave the old or the new content, never a torn file. */
    fun writeAtomic(target: File, text: String) {
        target.parentFile?.let { ensureDir(it) }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot replace ${target.path}")
        }
    }

    fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Cannot create directory ${dir.path}")
        }
        return dir
    }

    fun copy(input: InputStream, output: OutputStream, onBytes: ((Long) -> Unit)? = null): Long {
        val buf = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            onBytes?.invoke(total)
        }
        return total
    }

    fun copyFile(src: File, dst: File) {
        dst.parentFile?.let { ensureDir(it) }
        src.inputStream().use { i -> dst.outputStream().use { o -> copy(i, o) } }
        dst.setLastModified(src.lastModified())
    }

    /** Recursive copy. Symbolic links are skipped: they could point outside the space. */
    fun copyTree(src: File, dst: File) {
        if (isSymlink(src)) return
        if (src.isDirectory) {
            ensureDir(dst)
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else if (src.isFile) {
            copyFile(src, dst)
        }
    }

    /** Recursive delete that does not follow symbolic links. Returns true when nothing is left. */
    fun deleteTree(f: File): Boolean {
        if (!isSymlink(f) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete() || !existsNoFollow(f)
    }

    fun clearDir(dir: File): Boolean {
        var ok = true
        dir.listFiles()?.forEach { ok = deleteTree(it) && ok }
        return ok
    }

    fun sizeOf(f: File): Long {
        if (isSymlink(f)) return 0
        if (f.isFile) return f.length()
        var total = 0L
        f.listFiles()?.forEach { total += sizeOf(it) }
        return total
    }

    fun isSymlink(f: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }

    private fun existsNoFollow(f: File): Boolean = try {
        java.nio.file.Files.exists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    } catch (_: Throwable) {
        f.exists()
    }

    /** True when [child] is [root] or lies inside it after resolving `..` and links. */
    fun isInside(root: File, child: File): Boolean {
        val r = root.canonicalPath
        val c = child.canonicalPath
        return c == r || c.startsWith(r + File.separator)
    }

    /** Resolves [relative] under [root], refusing paths that escape it. */
    fun resolveInside(root: File, relative: String): File {
        val f = File(root, relative)
        if (!isInside(root, f)) throw SecurityException("Path escapes its root: $relative")
        return f
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
