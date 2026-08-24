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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.luisforlo.offlinetransfer.pairing.QrPairingRegistry
import com.luisforlo.offlinetransfer.security.SecuritySessionStore
import com.luisforlo.offlinetransfer.transfer.TransferCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException

class WifiDirectManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val channel = manager.initialize(appContext, Looper.getMainLooper()) {
        clearPendingTarget()
        cancelSecurityHandshake(resetSecurity = true)
        resetDisconnected("Canal Wi‑Fi Direct perdido")
    }

    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private var receiverRegistered = false
    private var pendingTargetAddress: String? = null
    private var pendingTargetName: String? = null
    private var pendingPreferGroupOwner = false
    private var targetSearchStartedAt = 0L
    private var targetSearchAttempt = 0
    private var securityCancellation: TransferCancellation? = null
    private var securityGeneration = 0

    private val targetRetry = Runnable {
        if (pendingTargetAddress != null && !_state.value.connected) {
            discoverPendingTarget()
        }
    }

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
                    } else if (!_state.value.connected) {
                        update { copy(status = status.takeUnless { it.startsWith("QR leído") } ?: status) }
                    } else {
                        clearPendingTarget()
                        cancelSecurityHandshake(resetSecurity = true)
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
        mainHandler.removeCallbacks(targetRetry)
        cancelSecurityHandshake(resetSecurity = true)
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
        if (pendingTargetAddress != null) {
            discoverPendingTarget()
            return
        }

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
        clearPendingTarget()
        connectDevice(device, preferGroupOwner)
    }

    fun connectByAddress(deviceAddress: String, preferGroupOwner: Boolean) {
        val normalized = deviceAddress.trim()
        require(normalized.isNotBlank()) { "Dirección Wi‑Fi Direct vacía" }

        clearPendingTarget(forgetRegistry = false)
        pendingTargetAddress = normalized
        pendingTargetName = QrPairingRegistry.nameFor(normalized)
        pendingPreferGroupOwner = preferGroupOwner
        targetSearchStartedAt = SystemClock.elapsedRealtime()
        targetSearchAttempt = 0

        update {
            copy(
                discovering = true,
                status = pendingTargetName?.let { "QR leído · buscando $it…" }
                    ?: "QR leído · buscando el teléfono correcto…",
            )
        }
        discoverPendingTarget()
    }

    @SuppressLint("MissingPermission")
    private fun discoverPendingTarget() {
        if (pendingTargetAddress == null) return
        if (targetSearchExpired()) {
            finishTargetTimeout()
            return
        }

        targetSearchAttempt++
        mainHandler.removeCallbacks(targetRetry)
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshThisDevice()
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                requestPeers()
                if (reason != WifiP2pManager.BUSY) {
                    update {
                        copy(status = "QR leído · reintentando búsqueda (${reasonText(reason)})…")
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: WifiP2pDevice, preferGroupOwner: Boolean) {
        mainHandler.removeCallbacks(targetRetry)
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
        clearPendingTarget()
        cancelSecurityHandshake(resetSecurity = true)
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
            val targetName = pendingTargetName

            val addressMatch = targetAddress?.let { address ->
                peers.firstOrNull { it.deviceAddress.equals(address, ignoreCase = true) }
            }

            val normalizedExpectedName = targetName?.let(::normalizeDeviceName).orEmpty()
            val nameMatches = if (normalizedExpectedName.isNotBlank()) {
                peers.filter { normalizeDeviceName(it.deviceName) == normalizedExpectedName }
            } else {
                emptyList()
            }
            val target = addressMatch ?: nameMatches.singleOrNull()

            if (target != null) {
                val preferOwner = pendingPreferGroupOwner
                val matchedByName = addressMatch == null
                clearPendingTarget()
                update {
                    copy(
                        peers = peers,
                        discovering = false,
                        status = if (matchedByName) {
                            "QR confirmado por nombre · conectando…"
                        } else {
                            "QR confirmado · conectando…"
                        },
                    )
                }
                connectDevice(target, preferOwner)
                return@requestPeers
            }

            if (targetAddress != null) {
                if (targetSearchExpired()) {
                    update { copy(peers = peers) }
                    finishTargetTimeout()
                } else {
                    val seconds = ((SystemClock.elapsedRealtime() - targetSearchStartedAt) / 1_000L)
                        .coerceAtLeast(0L)
                    update {
                        copy(
                            peers = peers,
                            discovering = true,
                            status = targetName?.let {
                                "QR leído · buscando $it… ${seconds}s"
                            } ?: "QR leído · buscando el receptor… ${seconds}s",
                        )
                    }
                    scheduleTargetRetry()
                }
            } else {
                update {
                    copy(
                        peers = peers,
                        discovering = false,
                        status = "${peers.size} dispositivo(s) encontrado(s)",
                    )
                }
            }
        }
    }

    private fun scheduleTargetRetry() {
        mainHandler.removeCallbacks(targetRetry)
        mainHandler.postDelayed(targetRetry, TARGET_RETRY_MS)
    }

    private fun targetSearchExpired(): Boolean =
        targetSearchStartedAt > 0L &&
            SystemClock.elapsedRealtime() - targetSearchStartedAt >= TARGET_TIMEOUT_MS

    private fun finishTargetTimeout() {
        val targetName = pendingTargetName
        clearPendingTarget()
        update {
            copy(
                discovering = false,
                status = targetName?.let {
                    "No encontramos $it en 20 s. Vuelve a escanear o usa conexión manual."
                } ?: "No encontramos el receptor en 20 s. Vuelve a escanear o usa conexión manual.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            clearPendingTarget()
            val formed = info.groupFormed
            val host = info.groupOwnerAddress?.hostAddress
            update {
                copy(
                    connected = formed,
                    groupOwnerAddress = host,
                    isGroupOwner = info.isGroupOwner,
                    discovering = false,
                    secureLinkEstablished = false,
                    securityVerificationCode = null,
                    status = if (formed) "Conexión Wi‑Fi Direct lista · preparando seguridad…" else "Negociando grupo…",
                )
            }
            if (formed && host != null) {
                startSecurityHandshake(info.isGroupOwner, host)
            }
        }
    }

    private fun startSecurityHandshake(isGroupOwner: Boolean, host: String) {
        cancelSecurityHandshake(resetSecurity = false)
        val hasSecurityContext = if (isGroupOwner) {
            SecuritySessionStore.hasReceiverSession()
        } else {
            SecuritySessionStore.hasSenderSession()
        }

        if (!hasSecurityContext) {
            update {
                copy(
                    secureLinkEstablished = false,
                    securityVerificationCode = null,
                    status = "Conexión Wi‑Fi Direct lista · conexión manual",
                )
            }
            return
        }

        val cancellation = TransferCancellation()
        securityCancellation = cancellation
        val generation = ++securityGeneration
        update { copy(status = "Conexión Wi‑Fi Direct lista · negociando cifrado E2E…") }

        Thread({
            try {
                val link = if (isGroupOwner) {
                    SecuritySessionStore.establishAsReceiver(cancellation)
                } else {
                    SecuritySessionStore.establishAsSender(host, cancellation)
                }
                mainHandler.post {
                    if (generation == securityGeneration && _state.value.connected) {
                        update {
                            copy(
                                secureLinkEstablished = true,
                                securityVerificationCode = link.verificationCode,
                                status = "🔒 E2E activo · código ${link.verificationCode} · verifica que coincida en ambos",
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                if (cancellation.isCancelled) return@Thread
                mainHandler.post {
                    if (generation == securityGeneration && _state.value.connected) {
                        val manualFallback = isGroupOwner && error is SocketTimeoutException
                        update {
                            copy(
                                secureLinkEstablished = false,
                                securityVerificationCode = null,
                                status = if (manualFallback) {
                                    "Conexión Wi‑Fi Direct lista · sin QR seguro (modo manual)"
                                } else {
                                    "⚠ No se pudo establecer cifrado E2E: ${error.message ?: error.javaClass.simpleName}"
                                },
                            )
                        }
                    }
                }
            }
        }, "offline-transfer-secure-handshake").start()
    }

    private fun cancelSecurityHandshake(resetSecurity: Boolean) {
        securityGeneration++
        securityCancellation?.cancel()
        securityCancellation = null
        if (resetSecurity) {
            SecuritySessionStore.reset()
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

    private fun clearPendingTarget(forgetRegistry: Boolean = true) {
        mainHandler.removeCallbacks(targetRetry)
        val address = pendingTargetAddress
        if (forgetRegistry && address != null) {
            QrPairingRegistry.forget(address)
        }
        pendingTargetAddress = null
        pendingTargetName = null
        pendingPreferGroupOwner = false
        targetSearchStartedAt = 0L
        targetSearchAttempt = 0
    }

    private fun resetDisconnected(status: String) {
        update {
            copy(
                discovering = false,
                peers = emptyList(),
                connected = false,
                groupOwnerAddress = null,
                isGroupOwner = false,
                secureLinkEstablished = false,
                securityVerificationCode = null,
                status = status,
            )
        }
    }

    private fun normalizeDeviceName(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun update(block: WifiDirectState.() -> WifiDirectState) {
        _state.value = _state.value.block()
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P no soportado"
        WifiP2pManager.BUSY -> "sistema ocupado"
        WifiP2pManager.ERROR -> "error interno"
        else -> "código $reason"
    }

    private companion object {
        const val TARGET_RETRY_MS = 1_500L
        const val TARGET_TIMEOUT_MS = 20_000L
    }
}
