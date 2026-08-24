package com.luisforlo.offlinetransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.luisforlo.offlinetransfer.pairing.QrCodeGenerator
import com.luisforlo.offlinetransfer.pairing.QrPairingPayload
import com.luisforlo.offlinetransfer.pairing.QrScannerView
import com.luisforlo.offlinetransfer.transfer.background.BackgroundTransferDirection
import com.luisforlo.offlinetransfer.transfer.background.BackgroundTransferPhase
import com.luisforlo.offlinetransfer.transfer.background.TransferForegroundService
import com.luisforlo.offlinetransfer.transfer.background.TransferRuntimeStore
import com.luisforlo.offlinetransfer.transport.wifidirect.WifiDirectManager
import com.luisforlo.offlinetransfer.transport.wifidirect.WifiDirectSession
import com.luisforlo.offlinetransfer.ui.theme.OfflineTransferTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var wifiDirect: WifiDirectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirect = WifiDirectSession.get(this)

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

@Composable
private fun ComponentActivity.App(wifiDirect: WifiDirectManager) {
    val activity = this
    val wifiState by wifiDirect.state.collectAsState()
    val transfer by TransferRuntimeStore.state.collectAsState()

    var desiredRole by remember { mutableStateOf(DesiredRole.SEND) }
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var nearbyPermissionGranted by remember { mutableStateOf(hasNearbyPermission()) }
    var cameraPermissionGranted by remember { mutableStateOf(hasCameraPermission()) }
    var notificationPermissionGranted by remember { mutableStateOf(hasNotificationPermission()) }
    var showQrScanner by remember { mutableStateOf(false) }
    var qrMessage by remember { mutableStateOf<String?>(null) }

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> nearbyPermissionGranted = granted }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        showQrScanner = granted
        if (!granted) qrMessage = "Se necesita permiso de cámara para escanear el QR."
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationPermissionGranted = granted }

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

    val localQrPayload = remember(wifiState.thisDeviceAddress, wifiState.thisDeviceName) {
        wifiState.thisDeviceAddress?.let { address ->
            QrPairingPayload.create(
                deviceAddress = address,
                deviceName = wifiState.thisDeviceName ?: "Android",
            )
        }
    }
    val localQrBitmap = remember(localQrPayload) {
        localQrPayload?.let { QrCodeGenerator.create(it.encode()) }
    }

    LaunchedEffect(Unit) {
        TransferForegroundService.refreshPartialSummary(activity)
        if (!nearbyPermissionGranted) {
            nearbyPermissionLauncher.launch(requiredNearbyPermission())
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(desiredRole, nearbyPermissionGranted, wifiState.connected) {
        if (desiredRole == DesiredRole.RECEIVE && nearbyPermissionGranted && !wifiState.connected) {
            wifiDirect.refreshThisDevice()
            wifiDirect.discoverPeers()
        }
    }

    val transferBusy = transfer.busy

    fun resetAndDisconnect() {
        if (transferBusy) return
        selectedFiles = emptyList()
        showQrScanner = false
        qrMessage = null
        TransferRuntimeStore.reset("Sesión cerrada. Elige rol y dispositivo.")
        wifiDirect.disconnect()
    }

    BackHandler(enabled = showQrScanner) {
        showQrScanner = false
        qrMessage = null
    }

    // During an active foreground transfer the normal Android Back action closes
    // only the Activity; the service and P2P session deliberately continue.
    BackHandler(enabled = wifiState.connected && !showQrScanner && !transferBusy) {
        resetAndDisconnect()
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
                Text("0.7.0-dev · segundo plano + E2E + reanudación persistente")
            }

            if (transferBusy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Servicio activo", style = MaterialTheme.typography.titleMedium)
                            Text("Puedes minimizar la app o apagar la pantalla. La transferencia continúa desde la notificación.")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { TransferForegroundService.pause(activity) }) {
                                    Text("Pausar")
                                }
                                OutlinedButton(onClick = { TransferForegroundService.cancel(activity) }) {
                                    Text("Cancelar")
                                }
                            }
                        }
                    }
                }
            }

            if (!wifiState.connected && !transferBusy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("1. ¿Qué quieres hacer?", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (desiredRole == DesiredRole.SEND) {
                                    Button(onClick = {
                                        desiredRole = DesiredRole.SEND
                                        qrMessage = null
                                    }) { Text("Enviar") }
                                } else {
                                    OutlinedButton(onClick = {
                                        desiredRole = DesiredRole.SEND
                                        qrMessage = null
                                    }) { Text("Enviar") }
                                }

                                if (desiredRole == DesiredRole.RECEIVE) {
                                    Button(onClick = {
                                        desiredRole = DesiredRole.RECEIVE
                                        qrMessage = null
                                    }) { Text("Recibir") }
                                } else {
                                    OutlinedButton(onClick = {
                                        desiredRole = DesiredRole.RECEIVE
                                        qrMessage = null
                                    }) { Text("Recibir") }
                                }
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
                            if (desiredRole == DesiredRole.RECEIVE) {
                                Text("2. Muestra este QR", style = MaterialTheme.typography.titleMedium)
                                Text("El otro teléfono debe elegir Enviar y escanearlo.")

                                if (localQrBitmap != null && localQrPayload != null) {
                                    Image(
                                        bitmap = localQrBitmap.asImageBitmap(),
                                        contentDescription = "QR para emparejar Offline Transfer",
                                        modifier = Modifier.size(260.dp),
                                    )
                                    Text(
                                        wifiState.thisDeviceName ?: "Este Android",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text("QR seguro listo · esperando al emisor…")
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Preparando identidad Wi-Fi Direct…")
                                    OutlinedButton(onClick = {
                                        wifiDirect.refreshThisDevice()
                                        wifiDirect.discoverPeers()
                                    }) {
                                        Text("Reintentar")
                                    }
                                }
                            } else {
                                Text("2. Escanea el QR del receptor", style = MaterialTheme.typography.titleMedium)
                                Text("La app identificará y conectará automáticamente al teléfono correcto.")

                                if (showQrScanner) {
                                    QrScannerView(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(340.dp),
                                        onQrText = { raw ->
                                            val payload = QrPairingPayload.decode(raw)
                                            if (payload == null) {
                                                qrMessage = "Ese QR no pertenece a Offline Transfer."
                                                showQrScanner = false
                                            } else {
                                                qrMessage = "QR de ${payload.deviceName} leído · conectando…"
                                                showQrScanner = false
                                                desiredRole = DesiredRole.SEND
                                                wifiDirect.connectByAddress(
                                                    deviceAddress = payload.deviceAddress,
                                                    preferGroupOwner = false,
                                                )
                                            }
                                        },
                                        onError = { error ->
                                            qrMessage = "Error de cámara: ${error.message ?: error.javaClass.simpleName}"
                                        },
                                    )
                                    OutlinedButton(onClick = { showQrScanner = false }) {
                                        Text("Cancelar escaneo")
                                    }
                                } else {
                                    Button(
                                        enabled = nearbyPermissionGranted,
                                        onClick = {
                                            qrMessage = null
                                            if (cameraPermissionGranted) {
                                                showQrScanner = true
                                            } else {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                    ) {
                                        Text("Escanear QR")
                                    }
                                }
                            }

                            qrMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            if (!transferBusy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                if (wifiState.connected) "Wi-Fi Direct" else "Conexión manual · respaldo",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(wifiState.status)
                            Text(if (wifiState.enabled) "P2P disponible" else "P2P no disponible o desactivado")

                            if (wifiState.connected) {
                                Text(
                                    if (wifiState.isGroupOwner) {
                                        "MODO RECEPTOR · recepción continua"
                                    } else {
                                        "MODO EMISOR · uno o varios archivos"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("Host del grupo: ${wifiState.groupOwnerAddress ?: "—"}")
                                Text(
                                    if (wifiState.secureLinkEstablished) {
                                        "🔐 AES-256-GCM · código ${wifiState.securityVerificationCode ?: "—"}"
                                    } else {
                                        "⚠ Sin cifrado E2E confirmado"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Perfil TCP: chunks 1 MiB · buffer solicitado 4 MiB",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                OutlinedButton(onClick = { resetAndDisconnect() }) {
                                    Text("Cambiar dispositivo / rol")
                                }
                            } else {
                                OutlinedButton(
                                    enabled = nearbyPermissionGranted,
                                    onClick = { wifiDirect.discoverPeers() },
                                ) {
                                    Text(if (wifiState.discovering) "Buscando…" else "Buscar manualmente")
                                }
                                if (!nearbyPermissionGranted) {
                                    OutlinedButton(
                                        onClick = { nearbyPermissionLauncher.launch(requiredNearbyPermission()) },
                                    ) { Text("Dar permiso Wi-Fi") }
                                }
                            }
                        }
                    }
                }
            }

            if (wifiState.peers.isNotEmpty() && !wifiState.connected && !showQrScanner && !transferBusy) {
                item { Text("Dispositivos encontrados", style = MaterialTheme.typography.titleMedium) }
                items(wifiState.peers, key = { it.deviceAddress }) { peer ->
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
                                onClick = {
                                    wifiDirect.connect(
                                        device = peer,
                                        preferGroupOwner = desiredRole == DesiredRole.RECEIVE,
                                    )
                                },
                            ) {
                                Text(if (desiredRole == DesiredRole.RECEIVE) "Conectar para recibir" else "Conectar para enviar")
                            }
                        }
                    }
                }
            }

            if (wifiState.connected && !wifiState.isGroupOwner && !transferBusy) {
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
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                                Text("Seleccionar archivos")
                            }
                            Button(
                                enabled = selectedFiles.isNotEmpty() &&
                                    wifiState.groupOwnerAddress != null &&
                                    (!wifiState.status.contains("negociando cifrado", ignoreCase = true)),
                                onClick = {
                                    wifiState.groupOwnerAddress?.let { host ->
                                        TransferForegroundService.startSend(activity, selectedFiles, host)
                                    }
                                },
                            ) {
                                Text("Enviar en segundo plano")
                            }
                        }
                    }
                }
            }

            if (wifiState.connected && wifiState.isGroupOwner && !transferBusy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Recibir archivos", style = MaterialTheme.typography.titleMedium)
                            Text("La recepción continuará aunque minimices la app o apagues la pantalla.")
                            Button(onClick = { TransferForegroundService.startReceive(activity) }) {
                                Text("Iniciar recepción en segundo plano")
                            }
                            Text(
                                "Los archivos verificados se guardan en Descargas/Offline Transfer.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (transfer.partialCount > 0) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Transferencias recuperables", style = MaterialTheme.typography.titleMedium)
                            Text("${transfer.partialCount} parcial(es) · ${formatBytes(transfer.partialBytes)} guardados")
                            Text(
                                "Se conservarán aunque cierres la app y se usarán automáticamente al enviar el mismo archivo.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (wifiState.connected || transfer.phase != BackgroundTransferPhase.IDLE) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Transferencia", style = MaterialTheme.typography.titleMedium)
                            Text(transfer.message)
                            transfer.currentFile?.let { Text(it) }

                            if (transfer.fileCount > 0) {
                                Text(
                                    "Archivo ${transfer.fileIndex.coerceAtMost(transfer.fileCount)} de ${transfer.fileCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (transfer.fileIndex > 0 && transfer.direction == BackgroundTransferDirection.RECEIVE) {
                                Text("Archivo #${transfer.fileIndex}", style = MaterialTheme.typography.bodySmall)
                            }

                            if (transfer.bytesTotal > 0L) {
                                val progress = (transfer.bytesDone.toFloat() / transfer.bytesTotal.toFloat())
                                    .coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${formatBytes(transfer.bytesDone)} / ${formatBytes(transfer.bytesTotal)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (transferBusy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            if (transfer.speedBytesPerSecond > 0.0) {
                                Text(
                                    "${formatRate(transfer.speedBytesPerSecond)}" +
                                        (transfer.etaSeconds?.let { " · ${formatEta(it)} restantes" } ?: ""),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }

                            if (transfer.encrypted) {
                                Text(
                                    "🔐 AES-256-GCM · E2E" +
                                        (transfer.verificationCode?.let { " · código $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            if (transfer.resumedFromBytes > 0L) {
                                Text(
                                    "Reanudado desde ${formatBytes(transfer.resumedFromBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            transfer.diagnostics?.let {
                                Text("Diagnóstico", style = MaterialTheme.typography.titleSmall)
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }

                            if (transfer.phase == BackgroundTransferPhase.PAUSED) {
                                Text(
                                    "Para continuar, reconecta por QR y selecciona el mismo archivo; el parcial se detectará automáticamente.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            if (transfer.history.isNotEmpty()) {
                item { Text("Historial de esta sesión", style = MaterialTheme.typography.titleMedium) }
                items(transfer.history.take(12), key = { it.id }) { entry ->
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

private fun ComponentActivity.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun ComponentActivity.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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

private fun formatEta(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    return when {
        safeSeconds < 60L -> "${safeSeconds}s"
        safeSeconds < 3_600L -> "${safeSeconds / 60}m ${safeSeconds % 60}s"
        else -> "${safeSeconds / 3_600}h ${(safeSeconds % 3_600) / 60}m"
    }
}
