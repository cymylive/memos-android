package com.usememos.mobile

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Minimal zip helpers for backup (data dir -> stream) and restore (zip -> data dir).
 * Restore guards against zip-slip traversal.
 */
object ZipUtils {

    fun zipDirectory(dir: File, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(dir).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(entryName))
                try {
                    file.inputStream().use { it.copyTo(zos) }
                } catch (e: IOException) {
                    throw IOException("无法读取文件 $entryName（${e.message}）", e)
                } finally {
                    zos.closeEntry()
                }
            }
        }
    }

    fun unzipTo(input: InputStream, dir: File) {
        val canonicalRoot = dir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val target = File(dir, entry.name).canonicalFile
                if (!target.path.startsWith(canonicalRoot)) {
                    throw IOException("非法压缩路径: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}