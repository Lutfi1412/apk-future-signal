package com.example.futuressignal.data

import java.util.TreeMap

/**
 * Satu baris data per menit untuk satu koin di satu pasar. Semua angka; null = tidak tersedia (sel CSV kosong).
 * Ini BAHAN MENTAH untuk model: sinyal (mis. "dominasi >= 85% selama >= 15 pembaruan") diturunkan di notebook,
 * jadi ambang bisa diganti-ganti tanpa mengumpulkan data ulang.
 */
data class MinuteRow(
    val ts: Long,                    // detik Unix awal menit (UTC)
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val quoteVol: Double?,           // total nilai transaksi (USDT)        - hanya Binance langsung
    val takerBuyQuote: Double?,      // nilai transaksi BUY agresif (USDT)  - hanya Binance langsung
    val n: Int?,                     // jumlah event transaksi              - hanya Binance langsung
    val buyN: Int?,                  // jumlah event BUY                    - hanya Binance langsung
    val maxTradeUsdt: Double?,       // transaksi tunggal terbesar (USDT)   - hanya Binance langsung
    val buyPctEnd: Double,           // porsi BUY (%) di jendela transaksi, pembaruan terakhir menit itu
    val buyPctMean: Double,
    val buyPctMin: Double,
    val buyPctMax: Double,
    val winN: Int,                   // jumlah transaksi di jendela (ukuran jendela = limit)
    val winSec: Double,              // rentang waktu jendela (detik): makin kecil = pasar makin cepat
    val winRetPct: Double,           // perubahan harga transaksi tertua -> terbaru di jendela (%)
    val winRangePct: Double,         // (tertinggi - terendah) / terendah di jendela (%)
    val vwapDevPct: Double,          // selisih harga terhadap VWAP jendela (%)
    val ticks: Int,                  // jumlah pembaruan dalam menit itu
    val src: Int,                    // 1 = harga/volume dari transaksi mentah, 0 = dari cuplikan (server sendiri)
    val runBuy: List<Int>,           // per ambang: rangkaian terpanjang (jumlah pembaruan) dominasi BUY >= ambang
    val runSell: List<Int>,
    val durBuy: List<Double>,        // per ambang: rangkaian terlama (detik)
    val durSell: List<Double>
)

object MinuteCsv {
    fun header(thresholds: List<Int>): String {
        val cols = mutableListOf(
            "ts", "open", "high", "low", "close",
            "quote_vol", "taker_buy_quote", "n", "buy_n", "max_trade_usdt",
            "buy_pct_end", "buy_pct_mean", "buy_pct_min", "buy_pct_max",
            "win_n", "win_sec", "win_ret_pct", "win_range_pct", "vwap_dev_pct",
            "ticks", "src"
        )
        thresholds.forEach { cols += "run_b$it" }
        thresholds.forEach { cols += "run_s$it" }
        thresholds.forEach { cols += "dur_b$it" }
        thresholds.forEach { cols += "dur_s$it" }
        return cols.joinToString(",")
    }

    fun line(r: MinuteRow): String {
        val c = ArrayList<String>(48)
        c += r.ts.toString()
        c += LogCsv.num(r.open, 8)
        c += LogCsv.num(r.high, 8)
        c += LogCsv.num(r.low, 8)
        c += LogCsv.num(r.close, 8)
        c += r.quoteVol?.let { LogCsv.num(it, 2) } ?: ""
        c += r.takerBuyQuote?.let { LogCsv.num(it, 2) } ?: ""
        c += r.n?.toString() ?: ""
        c += r.buyN?.toString() ?: ""
        c += r.maxTradeUsdt?.let { LogCsv.num(it, 2) } ?: ""
        c += LogCsv.num(r.buyPctEnd, 2)
        c += LogCsv.num(r.buyPctMean, 2)
        c += LogCsv.num(r.buyPctMin, 2)
        c += LogCsv.num(r.buyPctMax, 2)
        c += r.winN.toString()
        c += LogCsv.num(r.winSec, 3)
        c += LogCsv.num(r.winRetPct, 4)
        c += LogCsv.num(r.winRangePct, 4)
        c += LogCsv.num(r.vwapDevPct, 4)
        c += r.ticks.toString()
        c += r.src.toString()
        r.runBuy.forEach { c += it.toString() }
        r.runSell.forEach { c += it.toString() }
        r.durBuy.forEach { c += LogCsv.num(it, 3) }
        r.durSell.forEach { c += LogCsv.num(it, 3) }
        return c.joinToString(",")
    }
}

/**
 * Mengubah rangkaian cuplikan (snapshot daftar transaksi) menjadi satu baris per menit per koin per pasar.
 *
 * "Pembaruan" = daftar transaksi berubah (sama seperti hitungan "berturut-turut" di mesin sinyal).
 * Rangkaian (run) untuk ambang T = jumlah pembaruan berturut-turut dengan porsi BUY >= T% (atau porsi SELL >= T%);
 * rangkaian terbawa melewati batas menit, dan dimulai ulang bila ada celah > 2 menit tanpa pembaruan.
 *
 * Harga & volume yang tepat diisi dari bar transaksi mentah (onRawBar, hanya Binance langsung).
 * Tanpa itu (server sendiri), harga diambil dari transaksi di jendela dan kolom volume dibiarkan kosong.
 * Baris menit M baru dikeluarkan saat sudah ada pembaruan di menit M+2 (memberi waktu bar mentah tiba).
 */
class MinuteRecorder(val thresholds: List<Int> = DEFAULT_THRESHOLDS) {

    companion object {
        val DEFAULT_THRESHOLDS = listOf(60, 70, 80, 85, 90)
        private const val GRACE_MINUTES = 2L
        private const val GAP_RESET_MS = 120_000L
        private const val RAW_KEEP = 10L
    }

    private val k = thresholds.size

    private class Acc(val minute: Long, k: Int) {
        var ticks = 0
        var sumBuy = 0.0
        var minBuy = 1000.0
        var maxBuy = -1000.0
        var lastBuy = 0.0
        val maxRunB = IntArray(k)
        val maxRunS = IntArray(k)
        val maxDurB = DoubleArray(k)
        val maxDurS = DoubleArray(k)
        var openTs = Long.MAX_VALUE
        var open = 0.0
        var closeTs = Long.MIN_VALUE
        var close = 0.0
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        var winN = 0
        var winSec = 0.0
        var winRet = 0.0
        var winRange = 0.0
        var vwapDev = 0.0
    }

    private class PairState(k: Int) {
        var last: List<Trade>? = null
        var lastTs = 0L
        val runB = IntArray(k)
        val runS = IntArray(k)
        val startB = LongArray(k)
        val startS = LongArray(k)
        var cur: Acc? = null
        val finished = TreeMap<Long, Acc>()
        val raw = HashMap<Long, Bar>()
    }

    private val states = HashMap<Pair<String, Market>, PairState>()
    private val ready = ArrayList<Pair<Pair<String, Market>, MinuteRow>>()

    /** Bar menit dari transaksi mentah (hanya Binance langsung). */
    @Synchronized
    fun onRawBar(symbol: String, market: Market, bar: Bar) {
        val st = states.getOrPut(symbol to market) { PairState(k) }
        st.raw[bar.ts / 60L] = bar
    }

    /** Panggil tiap ada cuplikan baru dari sumber data (kedua sumber). */
    @Synchronized
    fun onSnapshot(snapshot: Snapshot) {
        for ((symbol, markets) in snapshot) {
            for ((market, list) in markets) {
                if (list.isEmpty()) continue
                val st = states.getOrPut(symbol to market) { PairState(k) }
                if (st.last == list) continue          // tidak berubah = bukan "pembaruan"
                st.last = list
                update(symbol to market, st, list)
            }
        }
    }

    /** Ambil baris yang sudah siap ditulis. */
    @Synchronized
    fun drain(): List<Pair<Pair<String, Market>, MinuteRow>> {
        val out = ArrayList(ready)
        ready.clear()
        return out
    }

    /**
     * Keluarkan semua menit yang sudah selesai (dipanggil saat layanan berhenti / sumber diganti).
     * Menit yang sedang berjalan DIBUANG karena belum utuh.
     */
    @Synchronized
    fun flush() {
        for ((key, st) in states) {
            st.cur = null
            while (st.finished.isNotEmpty()) {
                val acc = st.finished.pollFirstEntry().value
                ready.add(key to toRow(acc, st))
            }
            st.last = null
        }
    }

    private fun update(key: Pair<String, Market>, st: PairState, list: List<Trade>) {
        val newest = list.first()
        val oldest = list.last()
        var buy = 0.0
        var sell = 0.0
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var totalUsdt = 0.0
        var coins = 0.0
        for (t in list) {
            if (t.isBuy) buy += t.qty else sell += t.qty
            if (t.price > hi) hi = t.price
            if (t.price < lo) lo = t.price
            totalUsdt += t.qty
            if (t.price > 0) coins += t.qty / t.price
        }
        val total = buy + sell
        if (total <= 0.0) return
        val buyPct = buy / total * 100.0
        val sellPct = 100.0 - buyPct

        // celah panjang tanpa pembaruan: rangkaian dimulai ulang
        if (st.lastTs != 0L && newest.ts - st.lastTs > GAP_RESET_MS) {
            st.runB.fill(0)
            st.runS.fill(0)
        }
        st.lastTs = newest.ts

        var minute = newest.ts / 60_000L
        val prev = st.cur
        if (prev != null) {
            if (minute > prev.minute) {
                st.finished[prev.minute] = prev
                st.cur = null
            } else {
                minute = prev.minute               // waktu mundur sedikit dilipat ke menit berjalan
            }
        }
        val acc = st.cur ?: Acc(minute, k).also { st.cur = it }

        // rangkaian dominasi per ambang (terbawa antar menit)
        for (i in 0 until k) {
            val t = thresholds[i].toDouble()
            if (buyPct >= t) {
                if (st.runB[i] == 0) st.startB[i] = newest.ts
                st.runB[i]++
            } else {
                st.runB[i] = 0
            }
            if (sellPct >= t) {
                if (st.runS[i] == 0) st.startS[i] = newest.ts
                st.runS[i]++
            } else {
                st.runS[i] = 0
            }
            if (st.runB[i] > acc.maxRunB[i]) acc.maxRunB[i] = st.runB[i]
            if (st.runS[i] > acc.maxRunS[i]) acc.maxRunS[i] = st.runS[i]
            val dB = if (st.runB[i] > 0) (newest.ts - st.startB[i]) / 1000.0 else 0.0
            val dS = if (st.runS[i] > 0) (newest.ts - st.startS[i]) / 1000.0 else 0.0
            if (dB > acc.maxDurB[i]) acc.maxDurB[i] = dB
            if (dS > acc.maxDurS[i]) acc.maxDurS[i] = dS
        }

        // ringkasan porsi BUY dan jendela
        acc.ticks++
        acc.sumBuy += buyPct
        if (buyPct < acc.minBuy) acc.minBuy = buyPct
        if (buyPct > acc.maxBuy) acc.maxBuy = buyPct
        acc.lastBuy = buyPct
        acc.winN = list.size
        acc.winSec = maxOf(0.0, (newest.ts - oldest.ts) / 1000.0)
        acc.winRet = if (oldest.price > 0) (newest.price - oldest.price) / oldest.price * 100.0 else 0.0
        acc.winRange = if (lo > 0) (hi - lo) / lo * 100.0 else 0.0
        val vwap = if (coins > 0) totalUsdt / coins else newest.price
        acc.vwapDev = if (vwap > 0) (newest.price - vwap) / vwap * 100.0 else 0.0

        // harga dari transaksi di jendela yang jatuh pada menit ini (cadangan bila tidak ada bar mentah)
        val mStart = acc.minute * 60_000L
        val mEnd = mStart + 60_000L
        for (t in list) {
            if (t.ts < mStart || t.ts >= mEnd) continue
            if (t.ts <= acc.openTs) { acc.openTs = t.ts; acc.open = t.price }
            if (t.ts > acc.closeTs) { acc.closeTs = t.ts; acc.close = t.price }
            if (t.price > acc.high) acc.high = t.price
            if (t.price < acc.low) acc.low = t.price
        }

        // keluarkan menit yang sudah cukup lama selesai
        val limit = minute - GRACE_MINUTES
        while (st.finished.isNotEmpty() && st.finished.firstKey() <= limit) {
            val done = st.finished.pollFirstEntry().value
            ready.add(key to toRow(done, st))
        }
        val it = st.raw.keys.iterator()
        while (it.hasNext()) if (it.next() < limit - RAW_KEEP) it.remove()
    }

    private fun toRow(a: Acc, st: PairState): MinuteRow {
        val raw = st.raw[a.minute]
        val hasPrice = a.openTs != Long.MAX_VALUE
        return MinuteRow(
            ts = a.minute * 60L,
            open = raw?.open ?: if (hasPrice) a.open else 0.0,
            high = raw?.high ?: if (hasPrice) a.high else 0.0,
            low = raw?.low ?: if (hasPrice) a.low else 0.0,
            close = raw?.close ?: if (hasPrice) a.close else 0.0,
            quoteVol = raw?.quoteVol,
            takerBuyQuote = raw?.takerBuyQuote,
            n = raw?.n,
            buyN = raw?.buyN,
            maxTradeUsdt = raw?.maxTradeUsdt,
            buyPctEnd = a.lastBuy,
            buyPctMean = if (a.ticks > 0) a.sumBuy / a.ticks else 0.0,
            buyPctMin = if (a.ticks > 0) a.minBuy else 0.0,
            buyPctMax = if (a.ticks > 0) a.maxBuy else 0.0,
            winN = a.winN,
            winSec = a.winSec,
            winRetPct = a.winRet,
            winRangePct = a.winRange,
            vwapDevPct = a.vwapDev,
            ticks = a.ticks,
            src = if (raw != null) 1 else 0,
            runBuy = a.maxRunB.toList(),
            runSell = a.maxRunS.toList(),
            durBuy = a.maxDurB.toList(),
            durSell = a.maxDurS.toList()
        )
    }
}