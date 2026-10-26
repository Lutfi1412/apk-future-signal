package com.example.futuressignal.signal

import android.content.Context
import com.example.futuressignal.data.Fmt
import com.example.futuressignal.data.Market
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.SignalStats
import com.example.futuressignal.data.Snapshot
import com.example.futuressignal.data.Trade
import com.example.futuressignal.data.WindowStats
import kotlin.math.roundToInt

/**
 * Pengaturan sinyal. Nilai DOMINASI dan BERTURUT-TURUT sekarang diatur dari layar utama aplikasi
 * (2 input + tombol Simpan), disimpan di Prefs, dan berlaku langsung tanpa restart layanan.
 * Nilai bawaan ada di Prefs.DEFAULT_DOMINANCE_PCT dan Prefs.DEFAULT_STREAK.
 */
object SignalConfig {
    /**
     * Ambang dominasi (0.0–1.0): porsi sisi buy (atau sell) terhadap total (buy + sell) dari seluruh transaksi
     * di daftar yang dikirim server (sesuai BUFFER_SIZE, mis. 10 atau 50). Syarat terpenuhi bila porsi >= ambang.
     */
    @Volatile
    var dominance: Double = Prefs.DEFAULT_DOMINANCE_PCT / 100.0
        private set

    /** Harus terpenuhi sekian kali berturut-turut, dihitung hanya saat data berubah. */
    @Volatile
    var requiredStreak: Int = Prefs.DEFAULT_STREAK
        private set

    /** Naik setiap pengaturan berubah, supaya engine mereset hitungan berturut-turut yang sedang berjalan. */
    @Volatile
    var version: Int = 0
        private set

    /** Jeda minimal antar notifikasi untuk aturan yang sama. */
    const val COOLDOWN_MS = 60_000L

    /** Baca pengaturan tersimpan. Aman dipanggil berulang: hitungan baru direset hanya kalau nilainya berubah. */
    @Synchronized
    fun load(ctx: Context) {
        val d = Prefs.dominancePct(ctx) / 100.0
        val s = Prefs.streak(ctx)
        if (d != dominance || s != requiredStreak) {
            dominance = d
            requiredStreak = s
            version++
        }
    }

    /** Simpan dari input layar utama lalu langsung diberlakukan. */
    @Synchronized
    fun save(ctx: Context, streak: Int, dominancePct: Double) {
        Prefs.setStreak(ctx, streak)
        Prefs.setDominancePct(ctx, dominancePct)
        load(ctx)
    }
}

enum class Side { BUY, SELL }

data class Signal(
    val id: String,
    val title: String,
    val message: String,
    val speech: String,
    // --- rincian untuk log aktivitas (opsional; sinyal tes boleh dikosongkan) ---
    val symbol: String = "",
    val market: String = "",
    val side: String = "",
    val buy: Double = 0.0,
    val sell: Double = 0.0,
    val sharePct: Int = 0,
    val streak: Int = 0,
    val trades: List<Trade> = emptyList(),
    val thresholdPct: Double = 0.0,          // ambang minimum unggul (%) yang berlaku saat sinyal
    val stats: WindowStats? = null           // ringkasan jendela transaksi (angka, untuk model)
)

/**
 * 8 aturan = 2 simbol (BTC, SOL) x 2 pasar (Trade/Future) x 2 sisi (BUY/SELL).
 * Dipanggil setiap ada snapshot baru dari WebSocket.
 */
class SignalEngine {
    private val lastLists = HashMap<String, List<Trade>>()
    private val streaks = HashMap<String, Int>()
    private val lastFired = HashMap<String, Long>()
    private var configVersion = -1

    @Synchronized
    fun onSnapshot(snapshot: Snapshot): List<Signal> {
        val signals = ArrayList<Signal>()
        val now = System.currentTimeMillis()

        // Pengaturan berubah -> hitungan berturut-turut dimulai dari awal (pakai ambang yang baru)
        if (configVersion != SignalConfig.version) {
            streaks.clear()
            configVersion = SignalConfig.version
        }
        val dominance = SignalConfig.dominance
        val needed = SignalConfig.requiredStreak

        // koin mengikuti data yang dikirim sumbernya (server sendiri atau Binance langsung)
        for (symbol in snapshot.keys.sorted()) {
            for (market in Market.values()) {
                val list = snapshot[symbol]?.get(market) ?: continue
                if (list.isEmpty()) continue

                // Hanya proses kalau data pasangan ini benar-benar berubah
                val key = "$symbol|${market.key}"
                if (lastLists[key] == list) continue
                lastLists[key] = list

                val buy = list.filter { it.isBuy }.sumOf { it.qty }
                val sell = list.filter { !it.isBuy }.sumOf { it.qty }
                val total = buy + sell
                val buyShare = if (total > 0) buy / total else 0.0
                val sellShare = if (total > 0) sell / total else 0.0

                for (side in Side.values()) {
                    val ruleKey = "$key|$side"
                    val share = if (side == Side.BUY) buyShare else sellShare

                    if (share >= dominance) {
                        val streak = (streaks[ruleKey] ?: 0) + 1
                        if (streak >= needed) {
                            streaks[ruleKey] = 0
                            if (now - (lastFired[ruleKey] ?: 0L) >= SignalConfig.COOLDOWN_MS) {
                                lastFired[ruleKey] = now
                                signals += buildSignal(symbol, market, side, share, buy, sell, list, needed, dominance * 100.0)
                            }
                        } else {
                            streaks[ruleKey] = streak
                        }
                    } else {
                        streaks[ruleKey] = 0
                    }
                }

                // ===== TESTING START (hapus blok ini kalau sudah selesai testing) =====
                // Notif uji coba: muncul tiap 3x data Future BTC berubah (min. jeda 20 detik),
                // tanpa syarat rasio apa pun.
//                if (symbol == "BTCUSDT" && market == Market.FUTURE) {
//                    val testKey = "TEST"
//                    val c = (streaks[testKey] ?: 0) + 1
//                    if (c >= 3) {
//                        streaks[testKey] = 0
//                        if (now - (lastFired[testKey] ?: 0L) >= 20_000L) {
//                            lastFired[testKey] = now
//                            signals += Signal(
//                                id = "TEST",
//                                title = "TES • Future BTC",
//                                message = "Notifikasi uji coba otomatis (hapus blok TESTING di SignalEngine.kt)",
//                                speech = "Tes notifikasi berhasil. Suara dan voice berfungsi."
//                            )
//                        }
//                    } else {
//                        streaks[testKey] = c
//                    }
//                }
                // ===== TESTING END =====
            }
        }
        return signals
    }

    private fun buildSignal(
        symbol: String,
        market: Market,
        side: Side,
        share: Double,
        buy: Double,
        sell: Double,
        trades: List<Trade>,
        needed: Int,
        thresholdPct: Double
    ): Signal {
        val coin = symbol.removeSuffix("USDT")
        val coinSpeech = when (coin) {
            "BTC" -> "bitcoin"
            "SOL" -> "solana"
            else -> coin
        }
        val pct = (share * 100).roundToInt()
        val aksi = if (side == Side.BUY) "beli" else "jual"
        val dominan = if (side == Side.BUY) "Pembeli" else "Penjual"

        return Signal(
            id = "$symbol|${market.key}|$side",
            title = "${side.name} • ${market.label} $coin",
            message = "${side.name} dominan $pct% (${needed}x berturut-turut) • " +
                    "Buy ${Fmt.compact(buy)} vs Sell ${Fmt.compact(sell)} USDT",
            speech = "Sinyal $aksi. ${market.label} $coinSpeech. $dominan dominan $pct persen.",
            symbol = symbol,
            market = market.label,
            side = side.name,
            buy = buy,
            sell = sell,
            sharePct = pct,
            streak = needed,
            trades = trades,
            thresholdPct = thresholdPct,
            stats = SignalStats.compute(trades)
        )
    }
}