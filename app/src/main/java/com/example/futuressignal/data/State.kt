package com.example.futuressignal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Data terbaru dari WebSocket (di memori), disimpan ke disk secara berkala agar widget tetap punya data. */
object SnapshotStore {
    @Volatile
    var snapshot: Snapshot? = null
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    @Volatile
    private var raw: String? = null

    @Volatile
    private var needsFlush = false

    /** rawJson = JSON asli dari server sendiri; null untuk Binance langsung (dibuat saat disimpan). */
    fun update(rawJson: String?, parsed: Snapshot) {
        raw = rawJson
        snapshot = parsed
        updatedAt = System.currentTimeMillis()
        needsFlush = true
    }

    fun flush(ctx: Context) {
        if (!needsFlush) return
        needsFlush = false
        val snap = snapshot ?: return
        // Disimpan untuk widget saat proses mati: dibatasi PERSIST_MAX transaksi per daftar agar tetap ringan
        val text = raw ?: SnapshotJson.encode(snap, PERSIST_MAX)
        Prefs.saveSnapshot(ctx, text, updatedAt)
    }

    /** Buang data lama (mis. saat sumber data diganti). */
    fun clear(ctx: Context) {
        snapshot = null
        raw = null
        needsFlush = false
        updatedAt = 0L
        Prefs.saveSnapshot(ctx, "{}", 0L)
    }

    private const val PERSIST_MAX = 50

    fun load(ctx: Context): Snapshot? {
        snapshot?.let { return it }
        val saved = Prefs.lastSnapshot(ctx) ?: return null
        val parsed = SnapshotParser.parse(saved) ?: return null
        snapshot = parsed
        updatedAt = Prefs.lastSnapshotAt(ctx)
        return parsed
    }
}

/** Status yang dibaca UI (service dan UI berjalan di proses yang sama). */
object ServiceState {
    val enabled = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    val lastData = MutableStateFlow(0L)
    val soundEnabled = MutableStateFlow(true)
    val feedInfo = MutableStateFlow("")          // status koneksi Binance langsung (untuk layar utama)
}