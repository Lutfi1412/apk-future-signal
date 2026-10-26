package com.example.futuressignal.data

/**
 * Satu bar 1 menit dari transaksi MENTAH (hanya mode Binance langsung). ts = detik Unix awal menit (UTC).
 * Dipakai MinuteRecorder untuk mengisi harga open/high/low/close dan volume yang tepat.
 */
data class Bar(
    val ts: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val quoteVol: Double,        // total nilai transaksi (USDT)
    val takerBuyQuote: Double,   // nilai transaksi yang diawali pembeli (BUY agresif); SELL = quoteVol - takerBuyQuote
    val n: Int,                  // jumlah event transaksi (spot: trade, futures: aggTrade)
    val buyN: Int,               // jumlah event BUY
    val maxTradeUsdt: Double     // transaksi tunggal terbesar di menit itu (USDT)
)

/**
 * Menyusun transaksi (urut waktu) menjadi bar 1 menit.
 *  - Menit pertama setelah (re)connect TIDAK utuh, jadi dibuang. Hanya bar yang utuh yang dikeluarkan.
 *  - Menit tanpa transaksi diisi bar datar (volume 0) hanya bila celahnya <= maxFillMinutes; celah lebih panjang
 *    dibiarkan kosong (bisa jadi koneksi bermasalah) agar tidak ada data palsu.
 *  - Waktu transaksi yang mundur sedikit dilipat ke menit yang sedang berjalan.
 */
class BarAggregator(private val maxFillMinutes: Int = 3) {
    private var started = false
    private var ignoreMin = 0L
    private var curMin = 0L
    private var hasLast = false
    private var lastClose = 0.0

    private var open = 0.0
    private var high = 0.0
    private var low = 0.0
    private var close = 0.0
    private var quote = 0.0
    private var takerBuy = 0.0
    private var n = 0
    private var buyN = 0
    private var maxTrade = 0.0

    @Synchronized
    fun reset() {
        started = false
        hasLast = false
        lastClose = 0.0
        clearAcc()
    }

    /** Mengembalikan bar yang selesai (0 atau lebih, urut waktu). */
    @Synchronized
    fun add(t: Trade): List<Bar> {
        val minute = t.ts / 60_000L
        if (!started) {                       // menit pertama: tidak utuh, abaikan
            started = true
            ignoreMin = minute
            curMin = minute
            return emptyList()
        }
        val m = maxOf(minute, curMin)
        if (m == curMin) {
            if (curMin != ignoreMin) accumulate(t)
            return emptyList()
        }

        val out = ArrayList<Bar>(2)
        if (curMin != ignoreMin && n > 0) {
            out += finish(curMin)
            lastClose = close
            hasLast = true
        }
        val gap = m - curMin - 1
        if (hasLast && gap in 1..maxFillMinutes.toLong()) {
            for (k in 1..gap) {
                out += Bar(
                    ts = (curMin + k) * 60L,
                    open = lastClose, high = lastClose, low = lastClose, close = lastClose,
                    quoteVol = 0.0, takerBuyQuote = 0.0, n = 0, buyN = 0, maxTradeUsdt = 0.0
                )
            }
        }
        curMin = m
        clearAcc()
        accumulate(t)
        return out
    }

    private fun accumulate(t: Trade) {
        if (n == 0) {
            open = t.price
            high = t.price
            low = t.price
        } else {
            if (t.price > high) high = t.price
            if (t.price < low) low = t.price
        }
        close = t.price
        quote += t.qty
        if (t.isBuy) {
            takerBuy += t.qty
            buyN++
        }
        n++
        if (t.qty > maxTrade) maxTrade = t.qty
    }

    private fun finish(minute: Long) = Bar(minute * 60L, open, high, low, close, quote, takerBuy, n, buyN, maxTrade)

    private fun clearAcc() {
        open = 0.0
        high = 0.0
        low = 0.0
        close = 0.0
        quote = 0.0
        takerBuy = 0.0
        n = 0
        buyN = 0
        maxTrade = 0.0
    }
}