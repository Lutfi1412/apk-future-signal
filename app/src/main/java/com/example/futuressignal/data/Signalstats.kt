package com.example.futuressignal.data

/**
 * Ringkasan jendela transaksi saat sinyal muncul. Semua angka, dan disimpan di log supaya tetap ada
 * walau daftar transaksi lengkap sudah diringkas (log lama). Bahan fitur untuk model sinyal.
 */
data class WindowStats(
    val n: Int,                  // jumlah transaksi dalam jendela
    val seconds: Double,         // rentang waktu jendela (detik): makin kecil = pasar makin cepat
    val buyCnt: Int,             // jumlah transaksi BUY (jumlah SELL = n - buyCnt)
    val maxTradeUsdt: Double,    // transaksi tunggal terbesar (USDT)
    val maxTradeSide: Int,       // sisi transaksi terbesar: +1 BUY, -1 SELL
    val priceOldest: Double,     // harga transaksi tertua di jendela
    val priceHigh: Double,
    val priceLow: Double,
    val vwap: Double             // harga rata-rata tertimbang volume di jendela
)

/** Harga setelah sekian waktu sejak sinyal (0 = belum tercatat). Dipakai sebagai label/hasil untuk model. */
data class Outcome(
    val px5m: Double = 0.0,
    val px15m: Double = 0.0,
    val px1h: Double = 0.0,
    val px4h: Double = 0.0,
    val px12h: Double = 0.0
)

object SignalStats {
    /** trades: terbaru di depan. null bila transaksi kurang dari 2 (tidak ada jendela yang bisa dihitung). */
    fun compute(trades: List<Trade>): WindowStats? {
        if (trades.size < 2) return null
        var buyCnt = 0
        var maxUsdt = 0.0
        var maxSide = 0
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var totalUsdt = 0.0
        var coins = 0.0
        for (t in trades) {
            if (t.isBuy) buyCnt++
            if (t.qty > maxUsdt) {
                maxUsdt = t.qty
                maxSide = if (t.isBuy) 1 else -1
            }
            if (t.price > hi) hi = t.price
            if (t.price < lo) lo = t.price
            totalUsdt += t.qty
            if (t.price > 0) coins += t.qty / t.price
        }
        val newest = trades.first()
        val oldest = trades.last()
        val seconds = maxOf(0.0, (newest.ts - oldest.ts) / 1000.0)
        val vwap = if (coins > 0) totalUsdt / coins else newest.price
        return WindowStats(trades.size, seconds, buyCnt, maxUsdt, maxSide, oldest.price, hi, lo, vwap)
    }
}