package com.luisforlo.offlinetransfer.transport.wifidirect

import android.content.Context

/**
 * Process-lifetime Wi-Fi Direct manager.
 *
 * A foreground transfer must not lose its P2P/security state merely because the
 * Activity is recreated, backgrounded, or removed from Recents. Android drops
 * dynamic receivers automatically when the process dies, so keeping one manager
 * for the process avoids duplicate receivers across Activity recreation.
 */
object WifiDirectSession {
    @Volatile
    private var instance: WifiDirectManager? = null

    fun get(context: Context): WifiDirectManager =
        instance ?: synchronized(this) {
            instance ?: WifiDirectManager(context.applicationContext).also {
                instance = it
                it.register()
            }
        }
}
