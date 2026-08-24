package com.luisforlo.offlinetransfer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
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
import com.luisforlo.offlinetransfer.transport.wifidirect.WifiDirectManager
import com.luisforlo.offlinetransfer.ui.theme.OfflineTransferTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

private enum class TransferPhase {
    IDLE,
    PREPARING,
    WAITING,
    TRANSFERRING,
    COMPLETE,
    ERROR,
}

private data class TransferUiState(
    val phase: TransferPhase = TransferPhase.IDLE,
    val message: String = "Sin transferencia activa",
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
)

@Composable
private fun ComponentActivity.App(wifiDirect: WifiDirectManager) {
    val activity = this
    val scope = rememberCoroutineScope()
    val state by wifiDirect.state.collectAsState()
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var permissionGranted by remember { mutableStateOf(hasNearbyPermission()) }
    var transferUi by remember { mutableStateOf(TransferUiState()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        selectedFile = uri
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        wifiDirect.register()
        onDispose { wifiDirect.unregister() }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(requiredNearbyPermission())
    }

    val transferBusy = transferUi.phase == TransferPhase.PREPARING ||
        transferUi.phase == TransferPhase.WAITING ||
        transferUi.phase == TransferPhase.TRANSFERRING

    fun receiveFile() {
        scope.launch {
            transferUi = TransferUiState(
                phase = TransferPhase.WAITING,
                message = "Esperando al teléfono emisor…",
            )
            try {
                val saved = withContext(Dispatchers.IO) {
                    var lastUiUpdate = 0L
                    AndroidFileTransfer.receiveAndSave(activity) { received, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUiUpdate >= 100L || received == total) {
                            lastUiUpdate = now
                            activity.runOnUiThread {
                                transferUi = TransferUiState(
                                    phase = TransferPhase.TRANSFERRING,
                                    message = "Recibiendo archivo…",
                                    bytesDone = received,
                                    bytesTotal = total,
                                )
                            }
                        }
                    }
                }
                transferUi = TransferUiState(
                    phase = TransferPhase.COMPLETE,
                    message = "✓ Archivo verificado y guardado en ${saved.location}",
                    bytesDone = saved.transfer.bytesTransferred,
                    bytesTotal = saved.transfer.header.sizeBytes,
                )
            } catch (error: Throwable) {
                transferUi = TransferUiState(
                    phase = TransferPhase.ERROR,
                    message = "Error al recibir: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun sendFile(uri: Uri, host: String) {
        scope.launch {
            transferUi = TransferUiState(
                phase = TransferPhase.PREPARING,
                message = "Calculando SHA-256 y preparando archivo…",
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    var lastUiUpdate = 0L
                    AndroidFileTransfer.send(activity, uri, host) { sent, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUiUpdate >= 100L || sent == total) {
                            lastUiUpdate = now
                            activity.runOnUiThread {
                                transferUi = TransferUiState(
                                    phase = TransferPhase.TRANSFERRING,
                                    message = if (sent == total) {
                                        "Archivo enviado; esperando verificación del receptor…"
                                    } else {
                                        "Enviando archivo…"
                                    },
                                    bytesDone = sent,
                                    bytesTotal = total,
                                )
                            }
                        }
                    }
                }

                transferUi = if (result.verified) {
                    TransferUiState(
                        phase = TransferPhase.COMPLETE,
                        message = "✓ Transferencia completa. El receptor confirmó SHA-256.",
                        bytesDone = result.bytesTransferred,
                        bytesTotal = result.header.sizeBytes,
                    )
                } else {
                    TransferUiState(
                        phase = TransferPhase.ERROR,
                        message = "El receptor detectó que SHA-256 no coincide.",
                    )
                }
            } catch (error: Throwable) {
                transferUi = TransferUiState(
                    phase = TransferPhase.ERROR,
                    message = "Error al enviar: ${error.message ?: error.javaClass.simpleName}",
                )
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
                Text("MVP 1 · Wi-Fi Direct + OTF1 + TCP + SHA-256")
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Wi-Fi Direct", style = MaterialTheme.typography.titleMedium)
                        Text(state.status)
                        Text(if (state.enabled) "P2P disponible" else "P2P no disponible o desactivado")

                        if (state.connected) {
                            Text(
                                if (state.isGroupOwner) {
                                    "MODO RECEPTOR · este teléfono recibirá el archivo"
                                } else {
                                    "MODO EMISOR · este teléfono enviará el archivo"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Host del grupo: ${state.groupOwnerAddress ?: "—"}")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = permissionGranted && !transferBusy,
                                onClick = { wifiDirect.discoverPeers() },
                            ) {
                                Text(if (state.discovering) "Buscando…" else "Buscar")
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
                item { Text("Dispositivos encontrados", style = MaterialTheme.typography.titleMedium) }
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
                                onClick = { wifiDirect.connect(peer) },
                            ) {
                                Text("Conectar")
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
                            Text("Enviar archivo", style = MaterialTheme.typography.titleMedium)
                            Text(selectedFile?.lastPathSegment ?: "Ningún archivo seleccionado")
                            OutlinedButton(
                                enabled = !transferBusy,
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                            ) {
                                Text("Seleccionar archivo")
                            }
                            Button(
                                enabled = selectedFile != null &&
                                    state.groupOwnerAddress != null &&
                                    !transferBusy,
                                onClick = {
                                    val uri = selectedFile
                                    val host = state.groupOwnerAddress
                                    if (uri != null && host != null) sendFile(uri, host)
                                },
                            ) {
                                Text("Enviar ahora")
                            }
                            Text(
                                "En el teléfono receptor pulsa primero “Preparar recepción” y luego envía desde aquí.",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                            Text("Recibir archivo", style = MaterialTheme.typography.titleMedium)
                            Text("Los archivos verificados se guardarán en Descargas/Offline Transfer.")
                            Button(
                                enabled = !transferBusy,
                                onClick = { receiveFile() },
                            ) {
                                Text(if (transferUi.phase == TransferPhase.COMPLETE) "Recibir otro archivo" else "Preparar recepción")
                            }
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
