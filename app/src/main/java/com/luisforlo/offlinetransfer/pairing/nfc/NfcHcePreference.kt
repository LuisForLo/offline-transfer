package com.luisforlo.offlinetransfer.pairing.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation

object NfcHcePreference {
    fun prefer(activity: Activity): Boolean {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return false
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        if (!adapter.isEnabled) return false

        val component = ComponentName(activity, NfcPairingHostService::class.java)
        return runCatching {
            CardEmulation.getInstance(adapter).setPreferredService(activity, component)
        }.getOrDefault(false)
    }

    fun clear(activity: Activity) {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching {
            CardEmulation.getInstance(adapter).unsetPreferredService(activity)
        }
    }
}
