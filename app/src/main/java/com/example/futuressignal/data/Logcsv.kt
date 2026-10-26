package com.example.futuressignal.data

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportSummary(val files: Int, val rows: Int, val minuteFiles: Int = 0)

/**
 * Ekspor log sinyal ke CSV (satu file per koin per pasar) dalam satu .zip.
 * Semua kolom berupa angka (waktu = Unix timestamp detik, sisi = +1/-1). Sel kosong = data tidak tersedia (NaN).
 */
object LogCsv {

    val COLUMNS = listOf(
        "ts", "hour_utc", "dow",
        "side", "price", "buy_usdt", "sell_usdt", "dom_pct", "thr_pct", "thr_streak",
        "win_n", "win_sec", "buy_cnt", "sell_cnt", "max_trade_usdt", "max_trade_side",
        "win_ret_pct", "win_range_pct", "vwap_dev_pct",
        "gap_prev_sec", "side_prev", "flip", "buy_n_1h", "sell_n_1h",
        "ret_5m_pct", "ret_15m_pct", "ret_1h_pct", "ret_4h_pct", "ret_12h_pct"
    )

    fun fileName(symbol: String, market: String): String = "${market.lowercase(Locale.US)}_$symbol.csv"

    /** Baris CSV untuk satu pasangan (koin + pasar), urut waktu naik. */
    fun rows(entriesOfPair: List<LogEntry>): List<String> {
        val sorted = entriesOfPair
            .filter { it.type == LogType.SIGNAL && it.trades.isNotEmpty() }
            .sortedBy { it.ts }
        val out = ArrayList<String>(sorted.size)
        var lo = 0
        var buyIn = 0
        var sellIn = 0
        var prev: LogEntry? = null
        for ((i, e) in sorted.withIndex()) {
            // jendela 1 jam sebelum baris ini: [lo, i)
            while (lo < i && sorted[lo].ts < e.ts - 3_600_000L) {
                if (sorted[lo].side == "BUY") buyIn-- else sellIn--
                lo++
            }
            out += buildRow(e, prev, buyIn, sellIn)
            if (e.side == "BUY") buyIn++ else sellIn++
            prev = e
        }
        return out
    }

    private fun buildRow(e: LogEntry, prev: LogEntry?, buyIn1h: Int, sellIn1h: Int): String {
        val side = if (e.side == "BUY") 1 else -1
        val price = e.trades.first().price
        val total = e.buy + e.sell
        val dom = if (total > 0) (if (side == 1) e.buy else e.sell) / total * 100.0 else null
        val st = e.stats ?: SignalStats.compute(e.trades)   // log lama: hitung dari daftar transaksi bila masih lengkap
        val tsSec = e.ts / 1000
        val z = Instant.ofEpochSecond(tsSec).atZone(ZoneOffset.UTC)

        fun ret(px: Double): String = if (px > 0 && price > 0) num((px - price) / price * 100.0, 4) else ""

        val cells = listOf(
            tsSec.toString(),
            z.hour.toString(),
            (z.dayOfWeek.value - 1).toString(),                                   // 0 = Senin ... 6 = Minggu
            side.toString(),
            num(price, 8),
            num(e.buy, 2),
            num(e.sell, 2),
            if (dom != null) num(dom, 2) else "",
            if (e.thrPct > 0) num(e.thrPct, 2) else "",
            if (e.streak > 0) e.streak.toString() else "",
            st?.n?.toString() ?: "",
            if (st != null) num(st.seconds, 3) else "",
            st?.buyCnt?.toString() ?: "",
            if (st != null) (st.n - st.buyCnt).toString() else "",
            if (st != null) num(st.maxTradeUsdt, 2) else "",
            st?.maxTradeSide?.toString() ?: "",
            if (st != null && st.priceOldest > 0) num((price - st.priceOldest) / st.priceOldest * 100.0, 4) else "",
            if (st != null && st.priceLow > 0) num((st.priceHigh - st.priceLow) / st.priceLow * 100.0, 4) else "",
            if (st != null && st.vwap > 0) num((price - st.vwap) / st.vwap * 100.0, 4) else "",
            if (prev != null) ((e.ts - prev.ts) / 1000).toString() else "",
            if (prev != null) (if (prev.side == "BUY") "1" else "-1") else "",
            if (prev != null) (if (prev.side == e.side) "0" else "1") else "",
            buyIn1h.toString(),
            sellIn1h.toString(),
            ret(e.outcome.px5m),
            ret(e.outcome.px15m),
            ret(e.outcome.px1h),
            ret(e.outcome.px4h),
            ret(e.outcome.px12h)
        )
        return cells.joinToString(",")
    }

    /** Angka tanpa notasi ilmiah dan tanpa nol di belakang koma yang tidak perlu. */
    fun num(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return ""
        var s = String.format(Locale.US, "%.${decimals}f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    /**
     * Tulis .zip berisi satu CSV per koin per pasar. Pasangan bawaan (koin default x Trade/Future) selalu ada
     * walau kosong, supaya susunan file konsisten; pasangan lain ikut bila punya log.
     */
    fun writeZip(
        out: OutputStream,
        entries: List<LogEntry>,
        defaultSymbols: List<String>,
        minuteFiles: List<File> = emptyList()      // data per menit -> dimasukkan ke folder menit/ di dalam zip
    ): ExportSummary {
        val signals = entries.filter { it.type == LogType.SIGNAL && it.symbol.isNotEmpty() && it.trades.isNotEmpty() }
        val grouped = signals.groupBy { it.symbol to it.market }

        val keys = LinkedHashSet<Pair<String, String>>()
        for (m in listOf("Trade", "Future")) for (s in defaultSymbols) keys.add(s to m)
        grouped.keys.sortedWith(compareBy({ it.second }, { it.first })).forEach { keys.add(it) }

        var totalRows = 0
        ZipOutputStream(out).use { zip ->
            for ((sym, mkt) in keys) {
                val rows = rows(grouped[sym to mkt].orEmpty())
                totalRows += rows.size
                zip.putNextEntry(ZipEntry(fileName(sym, mkt)))
                val text = buildString {
                    append(COLUMNS.joinToString(",")).append('\n')
                    rows.forEach { append(it).append('\n') }
                }
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (f in minuteFiles) {
                zip.putNextEntry(ZipEntry("menit/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README_kolom.txt"))
            zip.write((README + "\n\n" + MINUTE_README).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return ExportSummary(keys.size, totalRows, minuteFiles.size)
    }

    private val MINUTE_README = """
        ======================================================================
        DATA PER MENIT (folder menit/)
        ======================================================================
        Satu file per koin per pasar: future_BTCUSDT_1m.csv, trade_BTCUSDT_1m.csv, dst.
        Satu baris = satu menit (UTC), urut waktu naik. Menit tanpa pembaruan tidak punya baris
        (isi dengan reindex + ffill untuk harga); celah juga muncul saat layanan mati.
        Menggabungkan trade (spot) dan future: gabungkan dua file lewat kolom ts.

        HARGA (jalur harga untuk simulasi stop, target, dan likuidasi)
        ts              Unix timestamp detik, awal menit (UTC)
        open, high, low, close

        ALIRAN (hanya bila memakai Binance langsung; kosong untuk server sendiri)
        quote_vol       total nilai transaksi (USDT)
        taker_buy_quote nilai transaksi BUY agresif (USDT); SELL agresif = quote_vol - taker_buy_quote
        n, buy_n        jumlah event transaksi, dan yang BUY
        max_trade_usdt  transaksi tunggal terbesar (USDT)

        DOMINASI DI JENDELA TRANSAKSI (jendela = limit transaksi terakhir, bawaan 50)
        buy_pct_end     porsi BUY (%) berdasarkan nilai transaksi, pada pembaruan terakhir di menit itu
        buy_pct_mean, buy_pct_min, buy_pct_max   rata-rata, terendah, tertinggi selama menit itu
                        porsi SELL = 100 - buy_pct
        win_n           jumlah transaksi di jendela (periksa konsisten bila limit pernah diubah)
        win_sec         rentang waktu jendela (detik); makin kecil = pasar makin cepat
        win_ret_pct     perubahan harga transaksi tertua ke terbaru di jendela (%)
        win_range_pct   (tertinggi - terendah) / terendah di jendela (%)
        vwap_dev_pct    selisih harga terhadap VWAP jendela (%)

        RANGKAIAN BERTURUT-TURUT (ide inti sinyal)
        "Pembaruan" = daftar transaksi berubah. Untuk tiap ambang T = 60, 70, 80, 85, 90 (%):
        run_bT          rangkaian terpanjang di menit itu: jumlah pembaruan berturut-turut dengan porsi BUY >= T%
        run_sT          sama, untuk porsi SELL >= T%
        dur_bT, dur_sT  lama rangkaian terpanjang itu dalam detik (lebih mudah dibandingkan antar kondisi pasar)
        Rangkaian terbawa melewati batas menit, dan dimulai ulang setelah celah > 2 menit tanpa pembaruan.
        Contoh sinyal di notebook: BUY bila run_b85 >= 15 (atau dur_b85 >= 5 detik).
        Ambang dan jumlah bisa dicoba ulang tanpa mengumpulkan data lagi.

        LAIN-LAIN
        ticks           jumlah pembaruan di menit itu (kualitas data)
        src             1 = harga dan aliran dari transaksi mentah, 0 = dari cuplikan transaksi (server sendiri)

        PERINGATAN KEBOCORAN DATA
        Nilai di baris menit M baru diketahui di AKHIR menit M. Kalau dipakai untuk memprediksi, entri paling cepat
        di open menit M+1, dan label (gerak harga ke depan) harus dihitung dari menit M+1 dan sesudahnya.
    """.trimIndent()

    private val README = """
        Futures Signal - data log sinyal (CSV)

        Satu file = satu koin di satu pasar: trade_* (spot) dan future_*. Satu baris = satu sinyal, urut waktu naik.
        Semua kolom angka. Sel kosong = data tidak tersedia (dibaca sebagai NaN oleh pandas).

        WAKTU
        ts              Unix timestamp (detik, UTC). WIB = UTC+7
        hour_utc        jam dalam sehari, 0-23 (UTC)
        dow             hari dalam seminggu, 0=Senin ... 6=Minggu (UTC)

        SINYAL (saat sinyal muncul)
        side            +1 = sinyal BUY, -1 = sinyal SELL
        price           harga transaksi terbaru
        buy_usdt        total nilai transaksi BUY dalam jendela (USDT)
        sell_usdt       total nilai transaksi SELL dalam jendela (USDT)
        dom_pct         porsi sisi yang unggul terhadap total, dalam persen (presisi 2 desimal)
        thr_pct         ambang minimum unggul yang berlaku saat itu (persen); kosong pada log lama
        thr_streak      jumlah berturut-turut yang disyaratkan saat itu

        JENDELA TRANSAKSI (isi daftar transaksi saat sinyal muncul)
        win_n           jumlah transaksi dalam jendela
        win_sec         rentang waktu jendela (detik); makin kecil = pasar makin cepat
        buy_cnt         jumlah transaksi BUY
        sell_cnt        jumlah transaksi SELL
        max_trade_usdt  transaksi tunggal terbesar (USDT)
        max_trade_side  sisi transaksi terbesar: +1 BUY, -1 SELL
        win_ret_pct     perubahan harga dari transaksi tertua ke terbaru di jendela (persen)
        win_range_pct   (tertinggi - terendah) / terendah di jendela (persen)
        vwap_dev_pct    selisih harga terhadap VWAP jendela (persen)

        URUTAN SINYAL (dihitung dari log pasangan yang sama)
        gap_prev_sec    jarak ke sinyal sebelumnya (detik); kosong pada baris pertama
        side_prev       sisi sinyal sebelumnya (+1 / -1)
        flip            1 bila sisi berbeda dari sinyal sebelumnya, 0 bila sama
        buy_n_1h        jumlah sinyal BUY pada 1 jam sebelum baris ini
        sell_n_1h       jumlah sinyal SELL pada 1 jam sebelum baris ini

        HASIL / LABEL (perubahan harga setelah sinyal, persen, relatif terhadap price)
        ret_5m_pct, ret_15m_pct, ret_1h_pct, ret_4h_pct, ret_12h_pct
        Kosong bila belum sampai waktunya atau saat itu layanan tidak berjalan.
        Untuk hasil searah sinyal: ret_*_pct * side (positif = searah sinyal).

        CATATAN
        - Log yang dibuat sebelum fitur ini ada punya beberapa kolom kosong (thr_pct, ret_*, dan kolom jendela
          bila daftar transaksinya sudah diringkas).
        - Data dari log yang tersimpan saja: sinyal yang tidak sempat tercatat tidak ada di sini.
    """.trimIndent()
}