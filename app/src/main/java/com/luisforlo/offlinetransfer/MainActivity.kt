package com.luisforlo.offlinetransfer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.luisforlo.offlinetransfer.transport.wifidirect.WifiDirectManager
import com.luisforlo.offlinetransfer.ui.theme.OfflineTransferTheme

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

@Composable
private fun ComponentActivity.App(wifiDirect: WifiDirectManager) {
    val state by wifiDirect.state.collectAsState()
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var permissionGranted by remember { mutableStateOf(hasNearbyPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> selectedFile = uri }

    DisposableEffect(Unit) {
        wifiDirect.register()
        onDispose { wifiDirect.unregister() }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(requiredNearbyPermission())
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
                Text("MVP 0 · Wi‑Fi Direct + TCP + SHA‑256")
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Archivo", style = MaterialTheme.typography.titleMedium)
                        Text(selectedFile?.lastPathSegment ?: "Ningún archivo seleccionado")
                        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Text("Seleccionar archivo")
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
                        Text("Wi‑Fi Direct", style = MaterialTheme.typography.titleMedium)
                        Text(state.status)
                        Text(if (state.enabled) "P2P disponible" else "P2P no disponible o desactivado")
                        if (state.connected) {
                            Text("Conectado · ${if (state.isGroupOwner) "Group Owner" else "Cliente"}")
                            Text("Host: ${state.groupOwnerAddress ?: "—"}")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = permissionGranted,
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

            if (state.peers.isNotEmpty()) {
                item { Text("Dispositivos encontrados", style = MaterialTheme.typography.titleMedium) }
                items(state.peers, key = { it.deviceAddress }) { peer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.deviceName.ifBlank { "Android cercano" })
                                Text(peer.deviceAddress, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { wifiDirect.connect(peer) }) {
                                Text("Conectar")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Siguiente hito: conectar dos teléfonos y cablear este enlace al motor TCP para transferir el archivo seleccionado.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun ComponentActivity.requiredNearbyPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

private fun ComponentActivity.hasNearbyPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredNearbyPermission()) == PackageManager.PERMISSION_GRANTED
