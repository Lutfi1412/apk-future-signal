package com.example.futuressignal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.futuressignal.data.Prefs

/** Menyalakan kembali layanan setelah HP restart, kalau terakhir statusnya ON. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.isEnabled(context)) {
            SignalService.start(context)
        }
    }
}
