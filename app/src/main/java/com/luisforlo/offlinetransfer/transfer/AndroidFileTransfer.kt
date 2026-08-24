package com.luisforlo.offlinetransfer.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.luisforlo.offlinetransfer.protocol.TransferHeader
import com.luisforlo.offlinetransfer.security.SecuritySessionStore
import com.luisforlo.offlinetransfer.security.SecureSessionCrypto
import java.io.File

object AndroidFileTransfer {
    private const val COPY_BUFFER_SIZE = 1024 * 1024

    data class FileInfo(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    data class SavedResult(
        val transfer: TcpFileTransfer.Result,
        val location: String,
    )

    fun inspect(context: Context, uri: Uri): FileInfo {
        val resolver = context.contentResolver
        val metadata = readMetadata(context, uri)
        val sizeBytes = metadata.sizeBytes
            ?: resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
            ?: error("No se pudo determinar el tamaño de ${metadata.fileName}")

        return FileInfo(
            uri = uri,
            fileName = sanitizeFileName(metadata.fileName),
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = sizeBytes,
        )
    }

    fun send(
        context: Context,
        uri: Uri,
        host: String,
        secureLink: SecureSessionCrypto.SecureLink? = null,
        cancellation: TransferCancellation? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): TcpFileTransfer.Result {
        val resolver = context.contentResolver
        val info = inspect(context, uri)
        cancellation?.throwIfCancelled()

        val hashStartedAt = SystemClock.elapsedRealtime()
        val sha256 = resolver.openInputStream(uri)?.use { input ->
            FileHasher.sha256(input, cancellation)
        } ?: error("No se pudo abrir el archivo seleccionado")
        val hashElapsedMillis = (SystemClock.elapsedRealtime() - hashStartedAt).coerceAtLeast(1L)

        cancellation?.throwIfCancelled()
        val header = TransferHeader(
            fileName = info.fileName,
            mimeType = info.mimeType,
            sizeBytes = info.sizeBytes,
            sha256 = sha256,
        )

        val effectiveSecureLink = secureLink ?: SecuritySessionStore.linkForSend()
        val result = if (effectiveSecureLink != null) {
            SecureTcpFileTransfer.send(
                host = host,
                link = effectiveSecureLink,
                header = header,
                inputFactory = {
                    resolver.openInputStream(uri)
                        ?: error("No se pudo volver a abrir el archivo para enviarlo")
                },
                cancellation = cancellation,
                onProgress = onProgress,
            )
        } else {
            TcpFileTransfer.send(
                host = host,
                header = header,
                inputFactory = {
                    resolver.openInputStream(uri)
                        ?: error("No se pudo volver a abrir el archivo para enviarlo")
                },
                cancellation = cancellation,
                onProgress = onProgress,
            )
        }

        return result.copy(preparationElapsedMillis = hashElapsedMillis)
    }

    fun receiveAndSave(
        context: Context,
        secureLink: SecureSessionCrypto.SecureLink? = null,
        cancellation: TransferCancellation? = null,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): SavedResult {
        val temporary = File.createTempFile("offline-transfer-", ".part", context.cacheDir)

        try {
            val effectiveSecureLink = secureLink ?: SecuritySessionStore.linkForReceive()
            val result = if (effectiveSecureLink != null) {
                SecureTcpFileTransfer.receive(
                    destination = temporary,
                    link = effectiveSecureLink,
                    cancellation = cancellation,
                    onProgress = onProgress,
                )
            } else {
                TcpFileTransfer.receive(
                    destination = temporary,
                    cancellation = cancellation,
                    onProgress = onProgress,
                )
            }
            cancellation?.throwIfCancelled()
            check(result.verified) { "SHA-256 no coincide; el archivo recibido fue descartado" }

            val publishStartedAt = SystemClock.elapsedRealtime()
            val location = publishVerifiedFile(
                context = context,
                source = temporary,
                rawFileName = result.header.fileName,
                mimeType = result.header.mimeType,
            )
            val publishElapsedMillis =
                (SystemClock.elapsedRealtime() - publishStartedAt).coerceAtLeast(1L)

            return SavedResult(
                transfer = result.copy(publishElapsedMillis = publishElapsedMillis),
                location = location,
            )
        } finally {
            temporary.delete()
        }
    }

    private data class DocumentMetadata(
        val fileName: String,
        val sizeBytes: Long?,
    )

    private fun readMetadata(context: Context, uri: Uri): DocumentMetadata {
        var fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        var sizeBytes: Long? = null

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    fileName = cursor.getString(nameIndex)
                }

                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }

        return DocumentMetadata(fileName, sizeBytes)
    }

    private fun publishVerifiedFile(
        context: Context,
        source: File,
        rawFileName: String,
        mimeType: String,
    ): String {
        val fileName = sanitizeFileName(rawFileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloads(context, source, fileName, mimeType)
        } else {
            publishToAppDownloads(context, source, fileName)
        }
    }

    private fun publishToDownloads(
        context: Context,
        source: File,
        fileName: String,
        mimeType: String,
    ): String {
        val resolver = context.contentResolver
        val relativeDirectory = "${Environment.DIRECTORY_DOWNLOADS}/Offline Transfer"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android no permitió crear el archivo en Descargas")

        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            } ?: error("No se pudo abrir el destino en Descargas")

            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(destination, ready, null, null)
            return "$relativeDirectory/$fileName"
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun publishToAppDownloads(
        context: Context,
        source: File,
        fileName: String,
    ): String {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val directory = File(root, "Offline Transfer").apply { mkdirs() }
        val destination = uniqueFile(directory, fileName)
        source.copyTo(destination, overwrite = false)
        return destination.absolutePath
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val initial = File(directory, fileName)
        if (!initial.exists()) return initial

        val extension = initial.extension
        val base = if (extension.isBlank()) initial.name else initial.name.removeSuffix(".$extension")
        var index = 2
        while (true) {
            val candidateName = if (extension.isBlank()) {
                "$base ($index)"
            } else {
                "$base ($index).$extension"
            }
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val leaf = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .take(180)

        return when {
            leaf.isBlank() -> "archivo"
            leaf == "." || leaf == ".." -> "archivo"
            else -> leaf
        }
    }
}
