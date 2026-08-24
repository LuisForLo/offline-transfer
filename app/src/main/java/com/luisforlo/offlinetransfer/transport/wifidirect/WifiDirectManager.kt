package com.luisforlo.offlinetransfer.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiDirectManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, Looper.getMainLooper()) {
        resetDisconnected("Canal Wi‑Fi Direct perdido")
    }

    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private var receiverRegistered = false
    private var pendingTargetAddress: String? = null
    private var pendingPreferGroupOwner = false

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val value = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    update { copy(enabled = value == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                    }
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        resetDisconnected("Desconectado")
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice
                    }
                    device?.let(::updateThisDevice)
                }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        refreshThisDevice()
    }

    fun unregister() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    fun refreshThisDevice() {
        if (Build.VERSION.SDK_INT >= 29) {
            manager.requestDeviceInfo(channel) { device ->
                device?.let(::updateThisDevice)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        update { copy(discovering = true, status = "Buscando dispositivos…") }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshThisDevice()
                update { copy(status = "Descubrimiento iniciado") }
            }

            override fun onFailure(reason: Int) {
                update { copy(discovering = false, status = "No se pudo buscar: ${reasonText(reason)}") }
            }
        })
    }

    fun connect(device: WifiP2pDevice, preferGroupOwner: Boolean) {
        pendingTargetAddress = null
        connectDevice(device, preferGroupOwner)
    }

    @SuppressLint("MissingPermission")
    fun connectByAddress(deviceAddress: String, preferGroupOwner: Boolean) {
        val normalized = deviceAddress.trim()
        require(normalized.isNotBlank()) { "Dirección Wi‑Fi Direct vacía" }
        pendingTargetAddress = normalized
        pendingPreferGroupOwner = preferGroupOwner

        update {
            copy(
                discovering = true,
                status = "QR leído · buscando el teléfono correcto…",
            )
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                update {
                    copy(
                        discovering = false,
                        status = "QR leído, pero no se pudo buscar: ${reasonText(reason)}",
                    )
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: WifiP2pDevice, preferGroupOwner: Boolean) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = if (preferGroupOwner) 15 else 0
        }
        val desiredRole = if (preferGroupOwner) "receptor" else "emisor"
        update {
            copy(
                discovering = false,
                status = "Conectando con ${device.deviceName.ifBlank { "dispositivo" }} como $desiredRole…",
            )
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                update { copy(status = "Solicitud enviada · preferencia: $desiredRole") }
            }

            override fun onFailure(reason: Int) {
                update { copy(status = "Conexión falló: ${reasonText(reason)}") }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        pendingTargetAddress = null
        update { copy(status = "Desconectando…", discovering = false) }
        runCatching {
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                resetDisconnected("Desconectado · elige rol y dispositivo")
            }

            override fun onFailure(reason: Int) {
                manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        resetDisconnected("Conexión cancelada · elige rol y dispositivo")
                    }

                    override fun onFailure(cancelReason: Int) {
                        resetDisconnected(
                            "Desconectado localmente · ${reasonText(reason)} / ${reasonText(cancelReason)}",
                        )
                    }
                })
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        manager.requestPeers(channel) { peerList ->
            val peers = peerList.deviceList.sortedBy { it.deviceName.lowercase() }
            val targetAddress = pendingTargetAddress
            val target = targetAddress?.let { address ->
                peers.firstOrNull { it.deviceAddress.equals(address, ignoreCase = true) }
            }

            if (target != null) {
                val preferOwner = pendingPreferGroupOwner
                pendingTargetAddress = null
                update { copy(peers = peers, discovering = false) }
                connectDevice(target, preferOwner)
            } else {
                update {
                    copy(
                        peers = peers,
                        discovering = targetAddress != null,
                        status = if (targetAddress != null) {
                            "QR leído · esperando que aparezca el teléfono…"
                        } else {
                            "${peers.size} dispositivo(s) encontrado(s)"
                        },
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            pendingTargetAddress = null
            update {
                copy(
                    connected = info.groupFormed,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress,
                    isGroupOwner = info.isGroupOwner,
                    discovering = false,
                    status = if (info.groupFormed) "Conexión Wi‑Fi Direct lista" else "Negociando grupo…",
                )
            }
        }
    }

    private fun updateThisDevice(device: WifiP2pDevice) {
        update {
            copy(
                thisDeviceName = device.deviceName.takeIf { it.isNotBlank() },
                thisDeviceAddress = device.deviceAddress.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun resetDisconnected(status: String) {
        update {
            copy(
                discovering = false,
                peers = emptyList(),
                connected = false,
                groupOwnerAddress = null,
                isGroupOwner = false,
                status = status,
            )
        }
    }

    private fun update(block: WifiDirectState.() -> WifiDirectState) {
        _state.value = _state.value.block()
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P no soportado"
        WifiP2pManager.BUSY -> "sistema ocupado"
        WifiP2pManager.ERROR -> "error interno"
        else -> "código $reason"
    }
}
