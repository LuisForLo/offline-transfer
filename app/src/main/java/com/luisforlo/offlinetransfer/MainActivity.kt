package com.luisforlo.offlinetransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.luisforlo.offlinetransfer.transfer.AndroidFileTransfer
import com.luisforlo.offlinetransfer.transfer.TcpFileTransfer
import com.luisforlo.offlinetransfer.transfer.TransferCancellation
import com.luisforlo.offlinetransfer.transfer.TransferCancelledException
import com.luisforlo.offlinetransfer.transfer.TransferMetricsCalculator
import com.luisforlo.offlinetransfer.transport.wifidirect.WifiDirectManager
import com.luisforlo.offlinetransfer.ui.theme.OfflineTransferTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    private lateinit var wifiDirect: WifiDirectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirect = WifiDirectManager(this)

        setContent {
            OfflineTransferTheme {
                App(wifiDirect)
            }
        }
    }
}

private enum class DesiredRole {
    SEND,
    RECEIVE,
}

private enum class TransferPhase {
    IDLE,
    PREPARING,
    WAITING,
    TRANSFERRING,
    COMPLETE,
    CANCELLED,
    ERROR,
}

private data class TransferUiState(
    val phase: TransferPhase = TransferPhase.IDLE,
    val message: String = "Sin transferencia activa",
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val currentFile: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val diagnostics: String? = null,
)

private data class TransferHistoryEntry(
    val id: Long,
    val direction: String,
    val fileName: String,
    val sizeBytes: Long,
    val detail: String,
)

@Composable
private fun ComponentActivity.App(wifiDirect: WifiDirectManager) {
    val activity = this
    val scope = rememberCoroutineScope()
    val state by wifiDirect.state.collectAsState()

    var desiredRole by remember { mutableStateOf(DesiredRole.SEND) }
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var permissionGranted by remember { mutableStateOf(hasNearbyPermission()) }
    var transferUi by remember { mutableStateOf(TransferUiState()) }
    var transferCancellation by remember { mutableStateOf<TransferCancellation?>(null) }
    var history by remember { mutableStateOf<List<TransferHistoryEntry>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        selectedFiles = uris
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        wifiDirect.register()
        onDispose {
            transferCancellation?.cancel()
            wifiDirect.unregister()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(requiredNearbyPermission())
    }

    val transferBusy = transferUi.phase == TransferPhase.PREPARING ||
        transferUi.phase == TransferPhase.WAITING ||
        transferUi.phase == TransferPhase.TRANSFERRING

    fun resetAndDisconnect() {
        transferCancellation?.cancel()
        transferCancellation = null
        selectedFiles = emptyList()
        history = emptyList()
        transferUi = TransferUiState(message = "Sesión cerrada. Elige rol y dispositivo.")
        wifiDirect.disconnect()
    }

    BackHandler(enabled = state.connected) {
        resetAndDisconnect()
    }

    fun stopTransfer() {
        transferUi = transferUi.copy(message = "Cancelando transferencia…")
        transferCancellation?.cancel()
    }

    fun startReceiveSession() {
        val cancellation = TransferCancellation()
        transferCancellation = cancellation

        scope.launch {
            var receivedCount = 0
            try {
                while (!cancellation.isCancelled) {
                    transferUi = TransferUiState(
                        phase = TransferPhase.WAITING,
                        message = if (receivedCount == 0) {
                            "Receptor listo. Esperando archivos…"
                        } else {
                            "✓ $receivedCount archivo(s) recibido(s). Esperando el siguiente…"
                        },
                        fileIndex = receivedCount + 1,
                    )

                    var networkStartMillis = 0L
                    var lastUiUpdate = 0L
                    val saved = withContext(Dispatchers.IO) {
                        AndroidFileTransfer.receiveAndSave(
                            context = activity,
                            cancellation = cancellation,
                        ) { received, total ->
                            val now = SystemClock.elapsedRealtime()
                            if (networkStartMillis == 0L) networkStartMillis = now
                            if (now - lastUiUpdate >= 150L || received == total) {
                                lastUiUpdate = now
                                val metrics = TransferMetricsCalculator.calculate(
                                    bytesDone = received,
                                    bytesTotal = total,
                                    elapsedMillis = (now - networkStartMillis).coerceAtLeast(1L),
                                )
                                activity.runOnUiThread {
                                    transferUi = TransferUiState(
                                        phase = TransferPhase.TRANSFERRING,
                                        message = "Recibiendo archivo…",
                                        bytesDone = received,
                                        bytesTotal = total,
                                        fileIndex = receivedCount + 1,
                                        speedBytesPerSecond = metrics.bytesPerSecond,
                                        etaSeconds = metrics.etaSeconds,
                                    )
                                }
                            }
                        }
                    }

                    receivedCount++
                    val diagnostics = formatPerformance(
                        result = saved.transfer,
                        showPreparation = false,
                        showPublish = true,
                    )
                    history = listOf(
                        TransferHistoryEntry(
                            id = System.nanoTime(),
                            direction = "RECIBIDO",
                            fileName = saved.transfer.header.fileName,
                            sizeBytes = saved.transfer.bytesTransferred,
                            detail = "$diagnostics\n${saved.location}",
                        ),
                    ) + history

                    transferUi = TransferUiState(
                        phase = TransferPhase.WAITING,
                        message = "✓ ${saved.transfer.header.fileName} verificado. Esperando otro archivo…",
                        bytesDone = saved.transfer.bytesTransferred,
                        bytesTotal = saved.transfer.header.sizeBytes,
                        currentFile = saved.transfer.header.fileName,
                        fileIndex = receivedCount + 1,
                        diagnostics = diagnostics,
                    )
                }
            } catch (_: TransferCancelledException) {
                transferUi = TransferUiState(
                    phase = TransferPhase.CANCELLED,
                    message = if (receivedCount > 0) {
                        "Sesión finalizada. $receivedCount archivo(s) recibido(s) correctamente."
                    } else {
                        "Recepción detenida."
                    },
                )
            } catch (error: Throwable) {
                transferUi = TransferUiState(
                    phase = TransferPhase.ERROR,
                    message = "Error al recibir: ${error.message ?: error.javaClass.simpleName}",
                )
            } finally {
                transferCancellation = null
            }
        }
    }

    fun sendFiles(uris: List<Uri>, host: String) {
        val cancellation = TransferCancellation()
        transferCancellation = cancellation

        scope.launch {
            try {
                transferUi = TransferUiState(
                    phase = TransferPhase.PREPARING,
                    message = "Leyendo información de ${uris.size} archivo(s)…",
                    fileCount = uris.size,
                )

                val files = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        cancellation.throwIfCancelled()
                        AndroidFileTransfer.inspect(activity, uri)
                    }
                }
                val totalBatchBytes = files.sumOf { it.sizeBytes }
                var completedBytes = 0L
                var lastDiagnostics: String? = null

                files.forEachIndexed { index, file ->
                    cancellation.throwIfCancelled()
                    transferUi = TransferUiState(
                        phase = TransferPhase.PREPARING,
                        message = "Calculando SHA-256 de ${file.fileName}…",
                        bytesDone = completedBytes,
                        bytesTotal = totalBatchBytes,
                        currentFile = file.fileName,
                        fileIndex = index + 1,
                        fileCount = files.size,
                    )

                    var networkStartMillis = 0L
                    var lastUiUpdate = 0L
                    val result = withContext(Dispatchers.IO) {
                        AndroidFileTransfer.send(
                            context = activity,
                            uri = file.uri,
                            host = host,
                            cancellation = cancellation,
                        ) { sent, fileTotal ->
                            val now = SystemClock.elapsedRealtime()
                            if (networkStartMillis == 0L) networkStartMillis = now
                            if (now - lastUiUpdate >= 150L || sent == fileTotal) {
                                lastUiUpdate = now
                                val metrics = TransferMetricsCalculator.calculate(
                                    bytesDone = sent,
                                    bytesTotal = fileTotal,
                                    elapsedMillis = (now - networkStartMillis).coerceAtLeast(1L),
                                )
                                val batchDone = completedBytes + sent
                                val remainingBatch = (totalBatchBytes - batchDone).coerceAtLeast(0L)
                                val batchEta = if (metrics.bytesPerSecond > 0.0) {
                                    ceil(remainingBatch / metrics.bytesPerSecond).toLong()
                                } else {
                                    null
                                }

                                activity.runOnUiThread {
                                    transferUi = TransferUiState(
                                        phase = TransferPhase.TRANSFERRING,
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
                                    )
                                }
                            }
                        }
                    }

                    check(result.verified) { "El receptor detectó que SHA-256 no coincide" }
                    completedBytes += file.sizeBytes
                    lastDiagnostics = formatPerformance(
                        result = result,
                        showPreparation = true,
                        showPublish = false,
                    )
                    history = listOf(
                        TransferHistoryEntry(
                            id = System.nanoTime(),
                            direction = "ENVIADO",
                            fileName = result.header.fileName,
                            sizeBytes = result.bytesTransferred,
                            detail = "SHA-256 verificado · $lastDiagnostics",
                        ),
                    ) + history
                }

                transferUi = TransferUiState(
                    phase = TransferPhase.COMPLETE,
                    message = "✓ ${files.size} archivo(s) transferido(s) y verificado(s).",
                    bytesDone = totalBatchBytes,
                    bytesTotal = totalBatchBytes,
                    fileIndex = files.size,
                    fileCount = files.size,
                    diagnostics = lastDiagnostics,
                )
            } catch (_: TransferCancelledException) {
                transferUi = TransferUiState(
                    phase = TransferPhase.CANCELLED,
                    message = "Envío cancelado.",
                )
            } catch (error: Throwable) {
                transferUi = TransferUiState(
                    phase = TransferPhase.ERROR,
                    message = "Error al enviar: ${error.message ?: error.javaClass.simpleName}",
                )
            } finally {
                transferCancellation = null
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Offline Transfer", style = MaterialTheme.typography.headlineMedium)
                Text("0.3.2-dev · roles elegibles + desconexión + Wi-Fi Direct optimizado")
            }

            if (!state.connected) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("1. ¿Qué quieres hacer?", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (desiredRole == DesiredRole.SEND) {
                                    Button(onClick = { desiredRole = DesiredRole.SEND }) {
                                        Text("Enviar")
                                    }
                                } else {
                                    OutlinedButton(onClick = { desiredRole = DesiredRole.SEND }) {
                                        Text("Enviar")
                                    }
                                }

                                if (desiredRole == DesiredRole.RECEIVE) {
                                    Button(onClick = { desiredRole = DesiredRole.RECEIVE }) {
                                        Text("Recibir")
                                    }
                                } else {
                                    OutlinedButton(onClick = { desiredRole = DesiredRole.RECEIVE }) {
                                        Text("Recibir")
                                    }
                                }
                            }
                            Text(
                                if (desiredRole == DesiredRole.RECEIVE) {
                                    "Este teléfono intentará convertirse en Group Owner para recibir."
                                } else {
                                    "Este teléfono intentará ser cliente para enviar al receptor."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (state.connected) "Wi-Fi Direct" else "2. Elige dispositivo",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(state.status)
                        Text(if (state.enabled) "P2P disponible" else "P2P no disponible o desactivado")

                        if (state.connected) {
                            val actualReceiving = state.isGroupOwner
                            Text(
                                if (actualReceiving) {
                                    "MODO RECEPTOR · recepción continua"
                                } else {
                                    "MODO EMISOR · uno o varios archivos"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Host del grupo: ${state.groupOwnerAddress ?: "—"}")
                            Text(
                                "Perfil TCP: chunks 1 MiB · buffer solicitado 4 MiB",
                                style = MaterialTheme.typography.bodySmall,
                            )

                            val roleMatches = (desiredRole == DesiredRole.RECEIVE) == actualReceiving
                            if (!roleMatches) {
                                Text(
                                    "Android negoció el rol contrario al elegido. Puedes desconectar y volver a intentarlo.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            OutlinedButton(
                                enabled = !transferBusy,
                                onClick = { resetAndDisconnect() },
                            ) {
                                Text("Cambiar dispositivo / rol")
                            }
                            Text(
                                "También puedes usar el gesto o botón Atrás para volver a elegir.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Button(
                                enabled = permissionGranted && !transferBusy,
                                onClick = { wifiDirect.discoverPeers() },
                            ) {
                                Text(if (state.discovering) "Buscando…" else "Buscar dispositivos")
                            }
                            if (!permissionGranted) {
                                OutlinedButton(
                                    onClick = { permissionLauncher.launch(requiredNearbyPermission()) },
                                ) {
                                    Text("Dar permiso")
                                }
                            }
                        }
                    }
                }
            }

            if (state.peers.isNotEmpty() && !state.connected) {
                item {
                    Text("Dispositivos encontrados", style = MaterialTheme.typography.titleMedium)
                }
                items(state.peers, key = { it.deviceAddress }) { peer ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(peer.deviceName.ifBlank { "Android cercano" })
                            Text(peer.deviceAddress, style = MaterialTheme.typography.bodySmall)
                            Button(
                                enabled = !transferBusy,
                                onClick = {
                                    wifiDirect.connect(
                                        device = peer,
                                        preferGroupOwner = desiredRole == DesiredRole.RECEIVE,
                                    )
                                },
                            ) {
                                Text(
                                    if (desiredRole == DesiredRole.RECEIVE) {
                                        "Conectar para recibir"
                                    } else {
                                        "Conectar para enviar"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (state.connected && !state.isGroupOwner) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Enviar archivos", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (selectedFiles.size) {
                                    0 -> "Ningún archivo seleccionado"
                                    1 -> "1 archivo seleccionado"
                                    else -> "${selectedFiles.size} archivos seleccionados"
                                },
                            )
                            OutlinedButton(
                                enabled = !transferBusy,
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                            ) {
                                Text("Seleccionar archivos")
                            }
                            Button(
                                enabled = selectedFiles.isNotEmpty() &&
                                    state.groupOwnerAddress != null &&
                                    !transferBusy,
                                onClick = {
                                    val host = state.groupOwnerAddress
                                    if (host != null) sendFiles(selectedFiles, host)
                                },
                            ) {
                                Text("Enviar ahora")
                            }
                            if (transferBusy) {
                                OutlinedButton(onClick = { stopTransfer() }) {
                                    Text("Cancelar envío")
                                }
                            }
                        }
                    }
                }
            }

            if (state.connected && state.isGroupOwner) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Recibir archivos", style = MaterialTheme.typography.titleMedium)
                            Text("La sesión queda escuchando para recibir varios archivos consecutivos.")
                            if (transferBusy) {
                                OutlinedButton(onClick = { stopTransfer() }) {
                                    Text("Detener recepción")
                                }
                            } else {
                                Button(onClick = { startReceiveSession() }) {
                                    Text("Iniciar recepción")
                                }
                            }
                            Text(
                                "Los archivos verificados se guardan en Descargas/Offline Transfer.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (state.connected || transferUi.phase != TransferPhase.IDLE) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Transferencia", style = MaterialTheme.typography.titleMedium)
                            Text(transferUi.message)

                            transferUi.currentFile?.let { fileName ->
                                Text(fileName, style = MaterialTheme.typography.bodyMedium)
                            }

                            if (transferUi.fileCount > 0) {
                                Text(
                                    "Archivo ${transferUi.fileIndex.coerceAtMost(transferUi.fileCount)} de ${transferUi.fileCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (transferUi.fileIndex > 0 && state.isGroupOwner) {
                                Text("Archivo #${transferUi.fileIndex}", style = MaterialTheme.typography.bodySmall)
                            }

                            if (transferUi.bytesTotal > 0L) {
                                val progress = (
                                    transferUi.bytesDone.toFloat() / transferUi.bytesTotal.toFloat()
                                    ).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${formatBytes(transferUi.bytesDone)} / ${formatBytes(transferUi.bytesTotal)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (transferBusy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            if (transferUi.speedBytesPerSecond > 0.0) {
                                Text(
                                    "${formatRate(transferUi.speedBytesPerSecond)}" +
                                        (transferUi.etaSeconds?.let { " · ${formatEta(it)} restantes" } ?: ""),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }

                            transferUi.diagnostics?.let { diagnostics ->
                                Text("Diagnóstico", style = MaterialTheme.typography.titleSmall)
                                Text(diagnostics, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Text("Historial de esta sesión", style = MaterialTheme.typography.titleMedium)
                }
                items(history.take(12), key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("${entry.direction} · ${entry.fileName}")
                            Text(formatBytes(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
                            Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun ComponentActivity.requiredNearbyPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

private fun ComponentActivity.hasNearbyPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredNearbyPermission()) == PackageManager.PERMISSION_GRANTED

private fun formatPerformance(
    result: TcpFileTransfer.Result,
    showPreparation: Boolean,
    showPublish: Boolean,
): String {
    val parts = mutableListOf<String>()
    parts += "Red ${formatRate(result.bytesPerSecond)} en ${formatDurationMs(result.transferElapsedMillis)}"

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
        safeSeconds < 60L -> "${safeSeconds}s"
        safeSeconds < 3_600L -> "${safeSeconds / 60}m ${safeSeconds % 60}s"
        else -> "${safeSeconds / 3_600}h ${(safeSeconds % 3_600) / 60}m"
    }
}
