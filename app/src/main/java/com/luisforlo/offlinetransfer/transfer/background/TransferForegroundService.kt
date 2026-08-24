package com.luisforlo.offlinetransfer.transfer.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.luisforlo.offlinetransfer.MainActivity
import com.luisforlo.offlinetransfer.security.SecuritySessionStore
import com.luisforlo.offlinetransfer.transfer.AndroidFileTransfer
import com.luisforlo.offlinetransfer.transfer.ResumeStore
import com.luisforlo.offlinetransfer.transfer.TcpFileTransfer
import com.luisforlo.offlinetransfer.transfer.TransferCancellation
import com.luisforlo.offlinetransfer.transfer.TransferCancelledException
import com.luisforlo.offlinetransfer.transfer.TransferMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil

class TransferForegroundService : Service() {
    private enum class StopRequest {
        NONE,
        PAUSE,
        CANCEL,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeCancellation: TransferCancellation? = null
    private var stopRequest = StopRequest.NONE
    private var lastNotificationUpdate = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        refreshPartialSummary(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> startSend(intent)
            ACTION_RECEIVE -> startReceive()
            ACTION_PAUSE -> requestStop(StopRequest.PAUSE)
            ACTION_CANCEL -> requestStop(StopRequest.CANCEL)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally keep the foreground service alive when the user dismisses the Activity.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        activeCancellation?.cancel()
        activeJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSend(intent: Intent) {
        if (activeJob?.isActive == true) return
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val uriStrings = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        if (host.isBlank() || uriStrings.isEmpty()) {
            publishTerminalError("Faltan archivos o dirección del receptor")
            return
        }

        val uris = uriStrings.map(Uri::parse)
        stopRequest = StopRequest.NONE
        val cancellation = TransferCancellation()
        activeCancellation = cancellation
        acquireWakeLock()
        promote(
            TransferRuntimeState(
                phase = BackgroundTransferPhase.PREPARING,
                direction = BackgroundTransferDirection.SEND,
                message = "Preparando ${uris.size} archivo(s)…",
                fileCount = uris.size,
                encrypted = SecuritySessionStore.linkOrNull() != null,
                verificationCode = SecuritySessionStore.verificationCodeOrNull(),
                partialCount = ResumeStore.count(this),
                partialBytes = ResumeStore.totalPartialBytes(this),
                history = TransferRuntimeStore.state.value.history,
            ),
        )

        activeJob = scope.launch {
            try {
                runSend(uris, host, cancellation)
            } catch (_: TransferCancelledException) {
                finishStoppedState(BackgroundTransferDirection.SEND)
            } catch (error: Throwable) {
                publishTerminalError("Error al enviar: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                activeCancellation = null
                activeJob = null
                releaseWakeLock()
                if (TransferRuntimeStore.state.value.phase != BackgroundTransferPhase.WAITING) {
                    stopForegroundAndSelf(keepNotification = true)
                }
            }
        }
    }

    private suspend fun runSend(
        uris: List<Uri>,
        host: String,
        cancellation: TransferCancellation,
    ) {
        val files = uris.map { uri ->
            cancellation.throwIfCancelled()
            AndroidFileTransfer.inspect(this, uri)
        }
        val totalBatchBytes = files.sumOf { it.sizeBytes }
        var completedBytes = 0L
        var lastDiagnostics: String? = null

        files.forEachIndexed { index, file ->
            cancellation.throwIfCancelled()
            updateState(
                phase = BackgroundTransferPhase.PREPARING,
                direction = BackgroundTransferDirection.SEND,
                message = "Calculando SHA-256 de ${file.fileName}…",
                bytesDone = completedBytes,
                bytesTotal = totalBatchBytes,
                currentFile = file.fileName,
                fileIndex = index + 1,
                fileCount = files.size,
            )

            var networkStartMillis = 0L
            var baselineBytes = -1L
            var lastUiUpdate = 0L
            val result = AndroidFileTransfer.send(
                context = this,
                uri = file.uri,
                host = host,
                cancellation = cancellation,
            ) { sent, fileTotal ->
                val now = SystemClock.elapsedRealtime()
                if (baselineBytes < 0L) {
                    baselineBytes = sent
                    networkStartMillis = now
                }
                if (now - lastUiUpdate >= UI_UPDATE_MS || sent == fileTotal) {
                    lastUiUpdate = now
                    val networkDone = (sent - baselineBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
                    val metrics = TransferMetricsCalculator.calculate(
                        bytesDone = networkDone,
                        bytesTotal = (fileTotal - baselineBytes.coerceAtLeast(0L)).coerceAtLeast(0L),
                        elapsedMillis = (now - networkStartMillis).coerceAtLeast(1L),
                    )
                    val batchDone = completedBytes + sent
                    val remainingBatch = (totalBatchBytes - batchDone).coerceAtLeast(0L)
                    val batchEta = if (metrics.bytesPerSecond > 0.0) {
                        ceil(remainingBatch / metrics.bytesPerSecond).toLong()
                    } else {
                        null
                    }

                    updateState(
                        phase = BackgroundTransferPhase.TRANSFERRING,
                        direction = BackgroundTransferDirection.SEND,
                        message = if (sent == fileTotal) {
                            "Archivo enviado; esperando verificación SHA-256…"
                        } else {
                            "Enviando ${file.fileName}…"
                        },
                        bytesDone = batchDone,
                        bytesTotal = totalBatchBytes,
                        currentFile = file.fileName,
                        fileIndex = index + 1,
                        fileCount = files.size,
                        speedBytesPerSecond = metrics.bytesPerSecond,
                        etaSeconds = batchEta,
                        resumedFromBytes = baselineBytes.coerceAtLeast(0L),
                    )
                }
            }

            check(result.verified) { "El receptor detectó que SHA-256 no coincide" }
            completedBytes += file.sizeBytes
            lastDiagnostics = formatPerformance(result, showPreparation = true, showPublish = false)
            appendHistory(
                BackgroundHistoryEntry(
                    id = System.nanoTime(),
                    direction = "ENVIADO",
                    fileName = result.header.fileName,
                    sizeBytes = result.bytesTransferred,
                    detail = buildString {
                        append(if (result.encrypted) "🔐 AES-256-GCM · " else "SIN CIFRADO · ")
                        append("SHA-256 verificado")
                        if (result.resumedFromBytes > 0L) {
                            append(" · reanudado desde ${formatBytes(result.resumedFromBytes)}")
                        }
                        append("\n$lastDiagnostics")
                    },
                ),
            )
        }

        val current = TransferRuntimeStore.state.value
        val finalState = current.copy(
            phase = BackgroundTransferPhase.COMPLETE,
            message = "✓ ${files.size} archivo(s) transferido(s) y verificado(s).",
            bytesDone = totalBatchBytes,
            bytesTotal = totalBatchBytes,
            fileIndex = files.size,
            fileCount = files.size,
            speedBytesPerSecond = 0.0,
            etaSeconds = null,
            diagnostics = lastDiagnostics,
        )
        TransferRuntimeStore.set(finalState)
        notifyState(finalState, force = true)
    }

    private fun startReceive() {
        if (activeJob?.isActive == true) return
        stopRequest = StopRequest.NONE
        val cancellation = TransferCancellation()
        activeCancellation = cancellation
        acquireWakeLock()
        val initial = TransferRuntimeState(
            phase = BackgroundTransferPhase.WAITING,
            direction = BackgroundTransferDirection.RECEIVE,
            message = "Receptor activo en segundo plano. Esperando archivos…",
            encrypted = SecuritySessionStore.linkOrNull() != null,
            verificationCode = SecuritySessionStore.verificationCodeOrNull(),
            partialCount = ResumeStore.count(this),
            partialBytes = ResumeStore.totalPartialBytes(this),
            history = TransferRuntimeStore.state.value.history,
        )
        promote(initial)

        activeJob = scope.launch {
            var receivedCount = 0
            try {
                while (!cancellation.isCancelled) {
                    refreshPartialSummary(this@TransferForegroundService)
                    updateState(
                        phase = BackgroundTransferPhase.WAITING,
                        direction = BackgroundTransferDirection.RECEIVE,
                        message = if (receivedCount == 0) {
                            "Receptor activo. Esperando archivos…"
                        } else {
                            "✓ $receivedCount archivo(s) recibido(s). Esperando el siguiente…"
                        },
                        fileIndex = receivedCount + 1,
                        speedBytesPerSecond = 0.0,
                        etaSeconds = null,
                    )

                    var networkStartMillis = 0L
                    var baselineBytes = -1L
                    var lastUiUpdate = 0L
                    val saved = AndroidFileTransfer.receiveAndSave(
                        context = this@TransferForegroundService,
                        cancellation = cancellation,
                    ) { received, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (baselineBytes < 0L) {
                            baselineBytes = received
                            networkStartMillis = now
                        }
                        if (now - lastUiUpdate >= UI_UPDATE_MS || received == total) {
                            lastUiUpdate = now
                            val networkDone = (received - baselineBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
                            val metrics = TransferMetricsCalculator.calculate(
                                bytesDone = networkDone,
                                bytesTotal = (total - baselineBytes.coerceAtLeast(0L)).coerceAtLeast(0L),
                                elapsedMillis = (now - networkStartMillis).coerceAtLeast(1L),
                            )
                            updateState(
                                phase = BackgroundTransferPhase.TRANSFERRING,
                                direction = BackgroundTransferDirection.RECEIVE,
                                message = "Recibiendo archivo…",
                                bytesDone = received,
                                bytesTotal = total,
                                fileIndex = receivedCount + 1,
                                speedBytesPerSecond = metrics.bytesPerSecond,
                                etaSeconds = metrics.etaSeconds,
                                resumedFromBytes = baselineBytes.coerceAtLeast(0L),
                            )
                        }
                    }

                    receivedCount++
                    val diagnostics = formatPerformance(saved.transfer, showPreparation = false, showPublish = true)
                    appendHistory(
                        BackgroundHistoryEntry(
                            id = System.nanoTime(),
                            direction = "RECIBIDO",
                            fileName = saved.transfer.header.fileName,
                            sizeBytes = saved.transfer.bytesTransferred,
                            detail = buildString {
                                append(if (saved.transfer.encrypted) "🔐 AES-256-GCM" else "SIN CIFRADO")
                                if (saved.transfer.resumedFromBytes > 0L) {
                                    append(" · reanudado desde ${formatBytes(saved.transfer.resumedFromBytes)}")
                                }
                                append("\n$diagnostics\n${saved.location}")
                            },
                        ),
                    )
                    refreshPartialSummary(this@TransferForegroundService)
                    updateState(
                        phase = BackgroundTransferPhase.WAITING,
                        direction = BackgroundTransferDirection.RECEIVE,
                        message = "✓ ${saved.transfer.header.fileName} verificado. Esperando otro archivo…",
                        bytesDone = saved.transfer.bytesTransferred,
                        bytesTotal = saved.transfer.header.sizeBytes,
                        currentFile = saved.transfer.header.fileName,
                        fileIndex = receivedCount + 1,
                        diagnostics = diagnostics,
                        speedBytesPerSecond = 0.0,
                        etaSeconds = null,
                        resumedFromBytes = saved.transfer.resumedFromBytes,
                    )
                }
            } catch (_: TransferCancelledException) {
                finishStoppedState(BackgroundTransferDirection.RECEIVE, receivedCount)
            } catch (error: Throwable) {
                publishTerminalError("Error al recibir: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                activeCancellation = null
                activeJob = null
                releaseWakeLock()
                stopForegroundAndSelf(keepNotification = true)
            }
        }
    }

    private fun requestStop(request: StopRequest) {
        stopRequest = request
        TransferRuntimeStore.update { current ->
            current.copy(
                message = if (request == StopRequest.PAUSE) {
                    "Pausando de forma segura…"
                } else {
                    "Deteniendo transferencia…"
                },
            )
        }
        notifyState(TransferRuntimeStore.state.value, force = true)
        activeCancellation?.cancel()
        if (activeJob == null) {
            finishStoppedState(TransferRuntimeStore.state.value.direction ?: BackgroundTransferDirection.SEND)
            stopForegroundAndSelf(keepNotification = true)
        }
    }

    private fun finishStoppedState(direction: BackgroundTransferDirection, receivedCount: Int = 0) {
        refreshPartialSummary(this)
        val paused = stopRequest == StopRequest.PAUSE
        val current = TransferRuntimeStore.state.value
        val next = current.copy(
            phase = if (paused) BackgroundTransferPhase.PAUSED else BackgroundTransferPhase.CANCELLED,
            direction = direction,
            message = if (paused) {
                if (current.partialCount > 0) {
                    "Pausado. El parcial quedó guardado para reanudar."
                } else {
                    "Transferencia pausada."
                }
            } else if (direction == BackgroundTransferDirection.RECEIVE && receivedCount > 0) {
                "Recepción detenida. $receivedCount archivo(s) completado(s)."
            } else {
                "Transferencia detenida."
            },
            speedBytesPerSecond = 0.0,
            etaSeconds = null,
        )
        TransferRuntimeStore.set(next)
        notifyState(next, force = true)
    }

    private fun publishTerminalError(message: String) {
        refreshPartialSummary(this)
        val current = TransferRuntimeStore.state.value
        val state = current.copy(
            phase = BackgroundTransferPhase.ERROR,
            message = message,
            speedBytesPerSecond = 0.0,
            etaSeconds = null,
        )
        TransferRuntimeStore.set(state)
        if (activeJob == null) promote(state) else notifyState(state, force = true)
    }

    private fun updateState(
        phase: BackgroundTransferPhase,
        direction: BackgroundTransferDirection,
        message: String,
        bytesDone: Long = TransferRuntimeStore.state.value.bytesDone,
        bytesTotal: Long = TransferRuntimeStore.state.value.bytesTotal,
        currentFile: String? = TransferRuntimeStore.state.value.currentFile,
        fileIndex: Int = TransferRuntimeStore.state.value.fileIndex,
        fileCount: Int = TransferRuntimeStore.state.value.fileCount,
        speedBytesPerSecond: Double = TransferRuntimeStore.state.value.speedBytesPerSecond,
        etaSeconds: Long? = TransferRuntimeStore.state.value.etaSeconds,
        diagnostics: String? = TransferRuntimeStore.state.value.diagnostics,
        resumedFromBytes: Long = TransferRuntimeStore.state.value.resumedFromBytes,
    ) {
        val previous = TransferRuntimeStore.state.value
        val state = previous.copy(
            phase = phase,
            direction = direction,
            message = message,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            currentFile = currentFile,
            fileIndex = fileIndex,
            fileCount = fileCount,
            speedBytesPerSecond = speedBytesPerSecond,
            etaSeconds = etaSeconds,
            diagnostics = diagnostics,
            encrypted = SecuritySessionStore.linkOrNull() != null || previous.encrypted,
            verificationCode = SecuritySessionStore.verificationCodeOrNull() ?: previous.verificationCode,
            resumedFromBytes = resumedFromBytes,
        )
        TransferRuntimeStore.set(state)
        notifyState(state)
    }

    private fun appendHistory(entry: BackgroundHistoryEntry) {
        TransferRuntimeStore.update { current ->
            current.copy(history = (listOf(entry) + current.history).take(20))
        }
    }

    private fun promote(state: TransferRuntimeState) {
        TransferRuntimeStore.set(state)
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyState(state: TransferRuntimeState, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdate < NOTIFICATION_UPDATE_MS) return
        lastNotificationUpdate = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: TransferRuntimeState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this,
            101,
            Intent(this, TransferForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            102,
            Intent(this, TransferForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val security = if (state.encrypted) "🔐 E2E" else "Sin cifrado"
        val title = when (state.direction) {
            BackgroundTransferDirection.SEND -> "Offline Transfer · Enviando"
            BackgroundTransferDirection.RECEIVE -> "Offline Transfer · Recibiendo"
            null -> "Offline Transfer"
        }
        val detail = buildString {
            state.currentFile?.let { append(it).append(" · ") }
            if (state.speedBytesPerSecond > 0.0) append(formatRate(state.speedBytesPerSecond)).append(" · ")
            append(security)
            state.etaSeconds?.let { append(" · ${formatEta(it)}") }
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (state.direction == BackgroundTransferDirection.RECEIVE) {
                    android.R.drawable.stat_sys_download
                } else {
                    android.R.drawable.stat_sys_upload
                },
            )
            .setContentTitle(title)
            .setContentText(if (detail.isBlank()) state.message else detail)
            .setSubText(state.message)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(state.busy)
            .setShowWhen(false)

        if (state.bytesTotal > 0L && state.busy) {
            val progress = ((state.bytesDone.coerceIn(0L, state.bytesTotal) * 1000L) / state.bytesTotal)
                .toInt()
            builder.setProgress(1000, progress, false)
        } else if (state.busy) {
            builder.setProgress(0, 0, true)
        }

        if (state.busy) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pausar", pause)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transferencias activas",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progreso de transferencias offline entre dispositivos"
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:transfer",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun stopForegroundAndSelf(keepNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (keepNotification) Service.STOP_FOREGROUND_DETACH else Service.STOP_FOREGROUND_REMOVE,
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(!keepNotification)
        }
        stopSelf()
    }

    companion object {
        private const val ACTION_SEND = "com.luisforlo.offlinetransfer.action.SEND"
        private const val ACTION_RECEIVE = "com.luisforlo.offlinetransfer.action.RECEIVE"
        private const val ACTION_PAUSE = "com.luisforlo.offlinetransfer.action.PAUSE"
        private const val ACTION_CANCEL = "com.luisforlo.offlinetransfer.action.CANCEL"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_URIS = "uris"
        private const val CHANNEL_ID = "offline_transfer_active"
        private const val NOTIFICATION_ID = 7001
        private const val UI_UPDATE_MS = 150L
        private const val NOTIFICATION_UPDATE_MS = 500L

        fun startSend(context: Context, uris: List<Uri>, host: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_HOST, host)
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map(Uri::toString)))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startReceive(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TransferForegroundService::class.java).setAction(ACTION_RECEIVE),
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, TransferForegroundService::class.java).setAction(ACTION_PAUSE),
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TransferForegroundService::class.java).setAction(ACTION_CANCEL),
            )
        }

        fun refreshPartialSummary(context: Context) {
            TransferRuntimeStore.update { current ->
                current.copy(
                    partialCount = ResumeStore.count(context),
                    partialBytes = ResumeStore.totalPartialBytes(context),
                )
            }
        }
    }
}

private fun formatPerformance(
    result: TcpFileTransfer.Result,
    showPreparation: Boolean,
    showPublish: Boolean,
): String {
    val parts = mutableListOf<String>()
    parts += "Red ${formatRate(result.bytesPerSecond)} en ${formatDurationMs(result.transferElapsedMillis)}"
    parts += if (result.encrypted) "🔐 AES-256-GCM · E2E" else "⚠ Sin cifrado"
    if (result.resumedFromBytes > 0L) {
        parts += "Reanudado desde ${formatBytes(result.resumedFromBytes)} · red nueva ${formatBytes(result.networkBytesTransferred)}"
    }
    if (showPreparation && result.preparationElapsedMillis > 0L) {
        val hashRate = result.bytesTransferred.toDouble() * 1_000.0 / result.preparationElapsedMillis.toDouble()
        parts += "SHA-256 ${formatRate(hashRate)} en ${formatDurationMs(result.preparationElapsedMillis)}"
    }
    if (showPublish && result.publishElapsedMillis > 0L) {
        val publishRate = result.bytesTransferred.toDouble() * 1_000.0 / result.publishElapsedMillis.toDouble()
        parts += "Guardado ${formatRate(publishRate)} en ${formatDurationMs(result.publishElapsedMillis)}"
    }
    if (result.effectiveSendBufferBytes > 0 || result.effectiveReceiveBufferBytes > 0) {
        parts += "TCP send ${formatBytes(result.effectiveSendBufferBytes.toLong())} · recv ${formatBytes(result.effectiveReceiveBufferBytes.toLong())}"
    }
    return parts.joinToString("\n")
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

private fun formatRate(bytesPerSecond: Double): String =
    "${formatBytes(bytesPerSecond.coerceAtLeast(0.0).toLong())}/s"

private fun formatDurationMs(milliseconds: Long): String = when {
    milliseconds < 1_000L -> "${milliseconds} ms"
    else -> String.format(Locale.getDefault(), "%.2f s", milliseconds / 1_000.0)
}

private fun formatEta(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    return when {
        safeSeconds < 60L -> "${safeSeconds}s restantes"
        safeSeconds < 3_600L -> "${safeSeconds / 60}m ${safeSeconds % 60}s restantes"
        else -> "${safeSeconds / 3_600}h ${(safeSeconds % 3_600) / 60}m restantes"
    }
}
