package com.usememos.mobile

import android.os.StatFs
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod

/**
 * Backup/restore zip helpers built on zip4j (ZIP64), so packages larger than
 * 4 GB are fully supported. Large media entries are stored uncompressed
 * (requires precomputed size/CRC) for near-copy speed; smaller files are
 * deflated. Restore keeps the zip-slip traversal guard and pre-checks
 * available disk space before extracting.
 */
object ZipUtils {

    // Entries at or above this size are stored without compression.
    private const val STORE_THRESHOLD = 50L * 1024 * 1024

    // 10% headroom beyond the summed uncompressed size for restore safety.
    private const val SPACE_HEADROOM_MULTIPLIER = 1.1

    private val mediaExtensions = setOf(
        "mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "ts",
        "mp3", "wav", "flac", "ogg", "oga", "aac", "m4a", "opus", "wma",
    )

    fun zipDirectory(dir: File, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(dir).invariantSeparatorsPath
                val store = file.length() > STORE_THRESHOLD || isMediaName(entryName)
                val params = ZipParameters().apply {
                    fileNameInZip = entryName
                    if (store) {
                        // zip4j requires known size and CRC for STORE entries;
                        // the CRC is precomputed with a streaming pass.
                        compressionMethod = CompressionMethod.STORE
                        entrySize = file.length()
                        entryCRC = file.crc32()
                    } else {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.NORMAL
                    }
                }
                zos.putNextEntry(params)
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

    /**
     * Extracts a backup zip into [dir]. The zip must be a local file because
     * zip4j needs random access to read the central directory (ZIP64).
     */
    fun unzipTo(zipFile: File, dir: File) {
        val zf = ZipFile(zipFile)
        val headers = zf.fileHeaders
        if (headers.isEmpty()) {
            throw IOException("备份文件为空，不是有效的备份")
        }
        val needed = headers.sumOf { it.uncompressedSize }
        ensureFreeSpace(dir.parentFile ?: dir, needed)
        val canonicalRoot = dir.canonicalPath + File.separator
        for (header in headers) {
            val target = File(dir, header.fileName).canonicalFile
            if (!target.path.startsWith(canonicalRoot)) {
                throw IOException("非法压缩路径: ${header.fileName}")
            }
            if (header.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zf.getInputStream(header).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun isMediaName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in mediaExtensions
    }

    private fun File.crc32(): Long {
        val crc = CRC32()
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun ensureFreeSpace(targetDir: File, neededBytes: Long) {
        if (neededBytes <= 0) return
        val available = StatFs(targetDir.absolutePath).availableBytes.toLong()
        val required = (neededBytes * SPACE_HEADROOM_MULTIPLIER).toLong()
        if (available < required) {
            val neededMb = required / (1024 * 1024)
            val haveMb = available / (1024 * 1024)
            throw IOException("磁盘空间不足：解压需要约 ${neededMb}MB，可用 ${haveMb}MB")
        }
    }
}
