package com.luisforlo.offlinetransfer.transport.wifidirect

import android.net.wifi.p2p.WifiP2pDevice

data class WifiDirectState(
    val enabled: Boolean = false,
    val discovering: Boolean = false,
    val peers: List<WifiP2pDevice> = emptyList(),
    val connected: Boolean = false,
    val groupOwnerAddress: String? = null,
    val isGroupOwner: Boolean = false,
    val thisDeviceName: String? = null,
    val thisDeviceAddress: String? = null,
    val secureLinkEstablished: Boolean = false,
    val securityVerificationCode: String? = null,
    val status: String = "Listo",
)
