package com.luisforlo.offlinetransfer.transfer

import android.content.Context
import com.luisforlo.offlinetransfer.protocol.TransferHeader
import java.io.File
import java.util.Properties

/** Persistent index for interrupted secure transfers. */
object ResumeStore {
    private const val DIRECTORY = "resumable-transfers"
    private const val PART_SUFFIX = ".part"
    private const val META_SUFFIX = ".meta"

    data class Entry(
        val sha256: String,
        val sizeBytes: Long,
        val fileName: String,
        val mimeType: String,
        val partialFile: File,
        val receivedBytes: Long,
    )

    fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Returns a deterministic persistent partial for this exact content identity.
     * The sidecar is rewritten atomically so a fresh process can rediscover it.
     */
    fun partialFor(context: Context, header: TransferHeader): File {
        val dir = directory(context)
        val key = key(header.sha256, header.sizeBytes)
        val partial = File(dir, "$key$PART_SUFFIX")
        writeManifest(File(dir, "$key$META_SUFFIX"), header)
        return partial
    }

    fun remove(context: Context, header: TransferHeader) {
        val dir = directory(context)
        val key = key(header.sha256, header.sizeBytes)
        File(dir, "$key$PART_SUFFIX").delete()
        File(dir, "$key$META_SUFFIX").delete()
    }

    fun list(context: Context): List<Entry> {
        val dir = directory(context)
        return dir.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .mapNotNull(::readEntry)
            .sortedByDescending { it.receivedBytes }
    }

    fun count(context: Context): Int = list(context).size

    fun totalPartialBytes(context: Context): Long = list(context).sumOf { it.receivedBytes }

    private fun readEntry(meta: File): Entry? = runCatching {
        val properties = Properties().apply {
            meta.inputStream().use(::load)
        }
        val sha = properties.getProperty("sha256") ?: return null
        val size = properties.getProperty("sizeBytes")?.toLongOrNull() ?: return null
        val name = properties.getProperty("fileName") ?: "archivo"
        val mime = properties.getProperty("mimeType") ?: "application/octet-stream"
        val part = File(meta.parentFile, "${key(sha, size)}$PART_SUFFIX")
        if (!part.exists()) {
            meta.delete()
            return null
        }
        if (part.length() > size) {
            part.delete()
            meta.delete()
            return null
        }
        Entry(
            sha256 = normalizeHash(sha),
            sizeBytes = size,
            fileName = name,
            mimeType = mime,
            partialFile = part,
            receivedBytes = part.length(),
        )
    }.getOrNull()

    private fun writeManifest(meta: File, header: TransferHeader) {
        val properties = Properties().apply {
            setProperty("sha256", normalizeHash(header.sha256))
            setProperty("sizeBytes", header.sizeBytes.toString())
            setProperty("fileName", header.fileName)
            setProperty("mimeType", header.mimeType)
        }
        val temp = File(meta.parentFile, "${meta.name}.tmp")
        temp.outputStream().use { properties.store(it, "Offline Transfer resume manifest") }
        if (!temp.renameTo(meta)) {
            temp.copyTo(meta, overwrite = true)
            temp.delete()
        }
    }

    private fun key(sha256: String, sizeBytes: Long): String =
        "${normalizeHash(sha256)}-$sizeBytes"

    private fun normalizeHash(raw: String): String {
        val hash = raw.lowercase().trim()
        require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "SHA-256 inválido para reanudación"
        }
        return hash
    }
}
