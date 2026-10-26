package com.example.futuressignal.data

/**
 * Pengolahan data dari Binance dibuat meniru server pribadi (binance.ts + store.ts), supaya bentuk datanya sama:
 *  - price  : harga
 *  - qty    : nilai dalam USDT (harga x jumlah koin), dibulatkan 2 desimal
 *  - time   : jam WIB (HH:MM:SS)
 *  - status : "SELL" bila buyer adalah maker, selain itu "BUY"
 *  - ts     : waktu transaksi (ms)
 *  Daftar selalu terbaru di depan dan dipotong sesuai limit.
 */
internal object FeedLogic {

    /** Sama dengan round2 di server: Math.round(n * 100) / 100. */
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /** Transaksi dari WebSocket: jumlah dalam koin, nilai USDT = harga x jumlah. */
    fun fromStream(price: String, qty: String, ts: Long, buyerIsMaker: Boolean): Trade? {
        val p = price.toDoubleOrNull() ?: return null
        val q = qty.toDoubleOrNull() ?: return null
        return Trade(p, round2(p * q), Fmt.time(ts), if (buyerIsMaker) "SELL" else "BUY", ts)
    }

    /** Transaksi dari REST (isi awal): nilai USDT langsung dari quoteQty. */
    fun fromRest(price: String, quoteQty: String, ts: Long, buyerIsMaker: Boolean): Trade? {
        val p = price.toDoubleOrNull() ?: return null
        val quote = quoteQty.toDoubleOrNull() ?: return null
        return Trade(p, round2(quote), Fmt.time(ts), if (buyerIsMaker) "SELL" else "BUY", ts)
    }

    /** Satu stream per koin per pasar. Spot memakai @trade, futures memakai @aggTrade (sama dengan server pribadi). */
    fun streamUrl(symbol: String, market: Market): String {
        val s = symbol.lowercase()
        return if (market == Market.SPOT) {
            "wss://stream.binance.com:9443/ws/$s@trade"
        } else {
            "wss://fstream.binance.com/market/ws/$s@aggTrade"
        }
    }

    fun restUrl(symbol: String, market: Market, limit: Int): String =
        if (market == Market.SPOT) {
            "https://api.binance.com/api/v3/trades?symbol=$symbol&limit=$limit"
        } else {
            "https://fapi.binance.com/fapi/v1/trades?symbol=$symbol&limit=$limit"
        }
}

/**
 * Penyangga satu daftar (satu koin, satu pasar). Terbaru di depan.
 * WebSocket dibuka lebih dulu; transaksi yang datang sebelum isi awal (REST) selesai ditampung,
 * lalu digabung (hanya yang lebih baru dari isi awal) supaya tidak ada yang hilang atau dobel.
 */
internal class PairBuffer(private val limit: Int) {
    private val items = ArrayDeque<Trade>()
    private val pending = ArrayList<Trade>()
    private var seeded = false

    @Synchronized
    fun push(t: Trade) {
        if (!seeded) {
            if (pending.size < MAX_PENDING) pending.add(t)
            return
        }
        items.addFirst(t)
        trim()
    }

    /** Isi awal dari REST (urutan terbaru di depan). */
    @Synchronized
    fun seed(newestFirst: List<Trade>) {
        items.clear()
        items.addAll(newestFirst.take(limit))
        val newest = newestFirst.firstOrNull()?.ts ?: Long.MIN_VALUE
        for (t in pending) if (t.ts > newest) items.addFirst(t)   // pending urut lama -> baru
        pending.clear()
        trim()
        seeded = true
    }

    /** Dipanggil sebelum menyambung ulang: kosongkan dan tunggu isi awal lagi. */
    @Synchronized
    fun reset() {
        items.clear()
        pending.clear()
        seeded = false
    }

    @Synchronized
    fun snapshot(): List<Trade> = items.toList()

    private fun trim() {
        while (items.size > limit) items.removeLast()
    }

    private companion object {
        const val MAX_PENDING = 5000
    }
}