package com.example.futuressignal.data

import android.content.Context

object Prefs {
    /** Ganti dengan alamat WebSocket Anda (bisa juga diubah dari layar aplikasi). */
    const val DEFAULT_URL = "wss://trades.domainanda.com/ws"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("signal_prefs", Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun wsUrl(c: Context): String = sp(c).getString("ws_url", DEFAULT_URL) ?: DEFAULT_URL
    fun setWsUrl(c: Context, v: String) = sp(c).edit().putString("ws_url", v).apply()

    /** true saat WebSocket sedang terhubung (dibaca widget untuk label LIVE/OFF). */
    fun isLive(c: Context): Boolean = sp(c).getBoolean("live", false)
    fun setLive(c: Context, v: Boolean) = sp(c).edit().putBoolean("live", v).apply()

    // ---- Pengaturan sinyal (diisi dari 2 input di layar utama) ----
    const val DEFAULT_STREAK = 15
    const val DEFAULT_DOMINANCE_PCT = 88.0

    /** Berapa kali berturut-turut (saat data berubah) syarat dominasi harus terpenuhi. */
    fun streak(c: Context): Int = sp(c).getInt("streak", DEFAULT_STREAK)
    fun setStreak(c: Context, v: Int) = sp(c).edit().putInt("streak", v).apply()

    /** Minimum porsi unggul dalam persen (disimpan sebagai teks agar angka desimal tidak bergeser). */
    fun dominancePct(c: Context): Double =
        sp(c).getString("dominance_pct", null)?.toDoubleOrNull() ?: DEFAULT_DOMINANCE_PCT
    fun setDominancePct(c: Context, v: Double) = sp(c).edit().putString("dominance_pct", v.toString()).apply()

    /** Rekam data per menit (bahan model). */
    fun recordMinutes(c: Context): Boolean = sp(c).getBoolean("record_minutes", true)
    fun setRecordMinutes(c: Context, v: Boolean) = sp(c).edit().putBoolean("record_minutes", v).apply()

    // ---- Sumber data: server sendiri atau Binance langsung ----
    fun source(c: Context): String = sp(c).getString("source", Source.SERVER) ?: Source.SERVER
    fun setSource(c: Context, v: String) = sp(c).edit().putString("source", v).apply()

    fun binanceConfig(c: Context): BinanceConfig {
        val p = sp(c)
        val symbols = FeedRules.parseSymbols(
            p.getString("bn_symbols", null) ?: FeedRules.DEFAULT_SYMBOLS.joinToString(",")
        ).symbols.ifEmpty { FeedRules.DEFAULT_SYMBOLS }
        val limit = p.getInt("bn_limit", FeedRules.DEFAULT_LIMIT).coerceIn(FeedRules.MIN_LIMIT, FeedRules.MAX_LIMIT)
        var spot = p.getBoolean("bn_spot", true)
        val future = p.getBoolean("bn_future", true)
        if (!spot && !future) spot = true
        return BinanceConfig(symbols, limit, spot, future)
    }

    fun setBinanceConfig(c: Context, cfg: BinanceConfig) = sp(c).edit()
        .putString("bn_symbols", cfg.symbols.joinToString(","))
        .putInt("bn_limit", cfg.limit)
        .putBoolean("bn_spot", cfg.spot)
        .putBoolean("bn_future", cfg.future)
        .apply()

    /** Koin yang dipantau (untuk widget dan tab analisis). */
    fun watchSymbols(c: Context): List<String> =
        if (source(c) == Source.BINANCE) binanceConfig(c).symbols else FeedRules.DEFAULT_SYMBOLS

    /** Suara notifikasi (bunyi notif.mp3 + suara bicara). Saat mati, notifikasi dan log tetap berjalan. */
    fun soundEnabled(c: Context): Boolean = sp(c).getBoolean("sound_enabled", true)
    fun setSoundEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("sound_enabled", v).apply()

    // ---- Kapasitas log: dibatasi (logMax entri) atau tanpa batas ----
    fun logUnlimited(c: Context): Boolean = sp(c).getBoolean("log_unlimited", false)
    fun logMax(c: Context): Int = sp(c).getInt("log_max", 300)
    fun setLogCapacity(c: Context, unlimited: Boolean, max: Int) =
        sp(c).edit().putBoolean("log_unlimited", unlimited).putInt("log_max", max).apply()

    /** Leverage yang dipakai halaman Analisis untuk menghitung target ROI. */
    fun leverage(c: Context): Int = sp(c).getInt("leverage", 10)
    fun setLeverage(c: Context, v: Int) = sp(c).edit().putInt("leverage", v).apply()

    fun lastSnapshot(c: Context): String? = sp(c).getString("snapshot", null)
    fun lastSnapshotAt(c: Context): Long = sp(c).getLong("snapshot_at", 0L)
    fun saveSnapshot(c: Context, raw: String, at: Long) =
        sp(c).edit().putString("snapshot", raw).putLong("snapshot_at", at).apply()
}