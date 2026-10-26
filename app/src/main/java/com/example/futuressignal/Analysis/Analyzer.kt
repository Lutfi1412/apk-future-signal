package com.example.futuressignal.Analysis

import com.example.futuressignal.data.Fmt
import com.example.futuressignal.data.LogEntry
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.LogType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Point(val ts: Long, val price: Double, val isBuy: Boolean, val sharePct: Int)
data class Bucket(val startTs: Long, val buy: Int, val sell: Int)
data class HitRate(
    val horizonMin: Int,
    val buyHits: Int,
    val buyTotal: Int,
    val sellHits: Int,
    val sellTotal: Int
)

data class Scenario(val title: String, val body: String)

enum class Confidence(val label: String) { LOW("Rendah"), MEDIUM("Sedang"), OK("Cukup") }

data class AnalysisResult(
    val n: Int,
    val buyN: Int,
    val sellN: Int,
    val firstTs: Long,
    val lastTs: Long,
    val spanMin: Double,
    val points: List<Point>,
    val buckets: List<Bucket>,
    val bucketMinutes: Int,
    val priceFirst: Double,
    val priceLast: Double,
    val priceHigh: Double,
    val priceLow: Double,
    val changePct: Double,
    val rangePct: Double,
    val flips: Int,
    val flipRate: Double,
    val longest: Int,
    val longestIsBuy: Boolean,
    val current: Int,
    val currentIsBuy: Boolean,
    val recentBuy: Int,
    val recentSell: Int,
    val oldBuy: Int,                   // sinyal SEBELUM jendela terbaru
    val oldSell: Int,
    val weightedBuyPct: Int,           // porsi BUY setelah diberi bobot waktu (0..100)
    val score: Int,                    // -100 (turun) .. +100 (naik)
    val direction: String,
    val confidence: Confidence,
    val notes: List<String>,           // peringatan tentang kualitas data
    val conclusions: List<String>,     // kesimpulan dari data log
    val targetInfo: List<String>,      // syarat target ROI, ruang likuidasi, perkiraan rentang 12-18 jam
    val scenarios: List<Scenario>
)

/**
 * Menganalisis log sinyal (yang belum dihapus) untuk satu pasangan simbol + pasar.
 * Semua angka berasal dari log: harga diambil dari transaksi terbaru saat sinyal muncul.
 * Skor arah adalah HEURISTIK (bukan probabilitas) dari keseimbangan sinyal berbobot waktu (baru > lama),
 * sinyal 30 menit terakhir, dan arah harga.
 */
object Analyzer {
    private const val RECENT_MIN = 30L

    /** Bobot sinyal turun separuh tiap sekian menit: sinyal baru lebih berpengaruh daripada yang lama. */
    private const val HALF_LIFE_MIN = 30.0
    private val HORIZONS = listOf(15, 30)

    fun analyze(
        all: List<LogEntry>,
        symbol: String,
        market: String,
        leverage: Int,
        now: Long
    ): AnalysisResult? {
        val sigs = all
            .filter { it.type == LogType.SIGNAL && it.symbol == symbol && it.market == market && it.trades.isNotEmpty() }
            .sortedBy { it.ts }
        if (sigs.isEmpty()) return null

        // ---------------------------------------------------------------- data dasar
        val pts = sigs.map { Point(it.ts, it.trades.first().price, it.side == "BUY", it.sharePct) }
        val n = pts.size
        val buyN = pts.count { it.isBuy }
        val sellN = n - buyN
        val first = pts.first().ts
        val last = pts.last().ts
        val spanMin = (last - first) / 60_000.0

        val pFirst = pts.first().price
        val pLast = pts.last().price
        val pHigh = pts.maxOf { it.price }
        val pLow = pts.minOf { it.price }
        val changePct = (pLast - pFirst) / pFirst * 100
        val rangePct = (pHigh - pLow) / pFirst * 100

        // ---------------------------------------------------------------- konsistensi arah
        var flips = 0
        for (i in 1 until n) if (pts[i].isBuy != pts[i - 1].isBuy) flips++
        val flipRate = if (n > 1) flips.toDouble() / (n - 1) else 0.0

        var longest = 1
        var longestIsBuy = pts[0].isBuy
        var cur = 1
        for (i in 1 until n) {
            cur = if (pts[i].isBuy == pts[i - 1].isBuy) cur + 1 else 1
            if (cur > longest) {
                longest = cur
                longestIsBuy = pts[i].isBuy
            }
        }
        val currentIsBuy = pts.last().isBuy

        val avgBuy = if (buyN > 0) pts.filter { it.isBuy }.map { it.sharePct }.average() else 0.0
        val avgSell = if (sellN > 0) pts.filter { !it.isBuy }.map { it.sharePct }.average() else 0.0

        // ---------------------------------------------------------------- sinyal terbaru
        var recent = pts.filter { it.ts >= last - RECENT_MIN * 60_000L }
        if (recent.size < 4) recent = pts.takeLast(minOf(5, n))
        val rb = recent.count { it.isBuy }
        val rs = recent.size - rb
        val recentSpanMin = ((last - recent.first().ts) / 60_000.0)

        // ---------------------------------------------------------------- skor arah (heuristik)
        // data lama = semua sinyal sebelum jendela terbaru
        val recentStart = recent.first().ts
        val old = pts.filter { it.ts < recentStart }
        val ob = old.count { it.isBuy }
        val os = old.size - ob

        // pembobotan waktu: bobot = 0.5 ^ (umur sinyal / waktu paruh)
        val halfLifeMs = HALF_LIFE_MIN * 60_000.0
        var wBuy = 0.0
        var wSell = 0.0
        for (p in pts) {
            val w = Math.pow(0.5, (last - p.ts) / halfLifeMs)
            if (p.isBuy) wBuy += w else wSell += w
        }
        val wTotal = wBuy + wSell
        val weightedBalance = if (wTotal > 0) (wBuy - wSell) / wTotal else 0.0
        val weightedBuyPct = if (wTotal > 0) (wBuy / wTotal * 100).roundToInt() else 50
        val recentBal = if (rb + rs > 0) (rb - rs).toDouble() / (rb + rs) else 0.0
        val pRange = pHigh - pLow
        val priceDir = if (pRange > 0) ((pLast - pFirst) / pRange).coerceIn(-1.0, 1.0) else 0.0
        var raw = 0.35 * weightedBalance + 0.35 * recentBal + 0.30 * priceDir
        if (flipRate > 0.5) raw *= 0.6                      // sinyal sering berbalik -> keyakinan arah diturunkan
        val score = (raw * 100).roundToInt().coerceIn(-100, 100)

        val confidence = when {
            n < 10 || spanMin < 60 -> Confidence.LOW
            n < 30 || spanMin < 360 -> Confidence.MEDIUM
            else -> Confidence.OK
        }

        // ---------------------------------------------------------------- grafik per bucket waktu
        val (bucketMinutes, buckets) = buildBuckets(pts, first, last, spanMin)

        // ---------------------------------------------------------------- uji ketepatan sinyal
        val hits = HORIZONS.map { hitRate(pts, it) }

        // ---------------------------------------------------------------- catatan kualitas data
        val disconnects = all.count {
            it.type == LogType.SYSTEM && it.ts in first..last &&
                    (it.title == "Terputus" || it.title == "Gagal terhubung")
        }
        val notes = ArrayList<String>()
        when (confidence) {
            Confidence.LOW -> notes += "Data masih sedikit ($n sinyal, ${spanText(spanMin)}). " +
                    "Anggap ini gambaran awal, bukan dasar keputusan."
            Confidence.MEDIUM -> notes += "Data tergolong sedang ($n sinyal, ${spanText(spanMin)}). " +
                    "Untuk horizon 12–18 jam, makin lama data dikumpulkan (beberapa jam hingga beberapa hari) makin berarti hasilnya."
            Confidence.OK -> {}
        }
        val staleMin = (now - last) / 60_000.0
        if (staleMin >= 30) {
            notes += "Sinyal terakhir tercatat ${spanText(staleMin)} yang lalu. Bisa berarti pasar sedang tidak dominan, " +
                    "atau layanan sempat tidak berjalan — kondisi sekarang mungkin sudah berbeda."
        }
        if (disconnects > 0) {
            notes += "Ada $disconnects kejadian koneksi putus/gagal di rentang ini, jadi sebagian sinyal mungkin terlewat."
        }
        val cap = LogStore.capacity
        if (cap != null && all.size >= cap) {
            notes += "Log sudah penuh ($cap entri); catatan paling lama otomatis terbuang. " +
                    "Naikkan kapasitas log atau pilih tanpa batas bila ingin analisis dari data yang lebih lama."
        }

        // ---------------------------------------------------------------- kesimpulan
        val conclusions = ArrayList<String>()

        val buyShareCount = buyN.toDouble() / n
        val bias = when {
            buyShareCount >= 0.65 -> "BUY"
            buyShareCount <= 0.35 -> "SELL"
            else -> "SEIMBANG"
        }
        conclusions += when (bias) {
            "BUY" -> "Sinyal BUY mendominasi: $buyN dari $n (${(buyShareCount * 100).roundToInt()}%), " +
                    "rata-rata kekuatan dominasi ${avgBuy.roundToInt()}%."
            "SELL" -> "Sinyal SELL mendominasi: $sellN dari $n (${((1 - buyShareCount) * 100).roundToInt()}%), " +
                    "rata-rata kekuatan dominasi ${avgSell.roundToInt()}%."
            else -> "Sinyal BUY ($buyN) dan SELL ($sellN) hampir seimbang — tidak ada sisi yang unggul di data ini."
        }

        if (n > 1) {
            val flipPct = (flipRate * 100).roundToInt()
            conclusions += when {
                flipRate >= 0.5 -> "Arah sering berbalik: berganti $flips× dari ${n - 1} sinyal berurutan ($flipPct%). " +
                        "Inilah yang membuat terasa kadang buy kadang sell — pola khas pasar bolak-balik (sideways), bukan tren yang bersih."
                flipRate >= 0.25 -> "Arah cukup campur: berganti $flips× dari ${n - 1} ($flipPct%). " +
                        "Ada kecenderungan, tetapi sering diselingi sinyal berlawanan."
                else -> "Arah cukup konsisten: hanya berganti $flips× dari ${n - 1} ($flipPct%)."
            } + " Rangkaian terpanjang $longest sinyal ${sideText(longestIsBuy)}; saat ini $cur sinyal ${sideText(currentIsBuy)} beruntun."
        }

        val weightedBias = when {
            weightedBuyPct >= 62 -> "BUY"
            weightedBuyPct <= 38 -> "SELL"
            else -> "SEIMBANG"
        }
        conclusions += "Dengan bobot waktu (sinyal lama dikurangi; bobot separuh tiap ${HALF_LIFE_MIN.roundToInt()} menit): " +
                "BUY $weightedBuyPct% vs SELL ${100 - weightedBuyPct}% → $weightedBias. " +
                if (weightedBias != bias) "Berbeda dari hitungan biasa ($bias): kondisi terbaru sudah menggeser gambaran lama."
                else "Searah dengan hitungan biasa ($bias)."

        val recentBias = when {
            rb > rs -> "condong BUY"
            rs > rb -> "condong SELL"
            else -> "imbang"
        }
        val shifted = (bias == "BUY" && rs > rb) || (bias == "SELL" && rb > rs)
        conclusions += "Terbaru (${recent.size} sinyal terakhir, ±${recentSpanMin.roundToInt()} menit): " +
                "$rb BUY vs $rs SELL → $recentBias." +
                if (shifted) " Ini berlawanan dengan kecenderungan keseluruhan — arah mungkin sedang bergeser." else ""

        if (old.size >= 3 && recent.size >= 3) {
            val oldBal = (ob - os).toDouble() / old.size
            val shift = recentBal - oldBal
            conclusions += "Lama vs baru: data lama (${old.size} sinyal) $ob BUY / $os SELL; data baru (${recent.size} sinyal) $rb BUY / $rs SELL." +
                    when {
                        shift >= 0.6 -> " Arah bergeser ke BUY. Untuk horizon 12–18 jam, ini bisa awal pembalikan atau sekadar riak jangka pendek — tunggu konfirmasi sinyal berikutnya."
                        shift <= -0.6 -> " Arah bergeser ke SELL. Untuk horizon 12–18 jam, ini bisa awal pembalikan atau sekadar riak jangka pendek — tunggu konfirmasi sinyal berikutnya."
                        else -> " Komposisinya relatif sama, jadi gambaran lama masih berlaku."
                    }
        }

        val trend = when {
            priceDir > 0.3 -> 1
            priceDir < -0.3 -> -1
            else -> 0
        }
        conclusions += "Harga di log: ${Fmt.price(pFirst)} → ${Fmt.price(pLast)} (${signed(changePct)}%), " +
                "rentang ${f2(rangePct)}% (terendah ${Fmt.price(pLow)}, tertinggi ${Fmt.price(pHigh)})."
        conclusions += when {
            bias == "BUY" && trend > 0 ->
                "Arus beli sejalan dengan harga yang naik — konfirmasi yang cukup baik untuk sisi naik (di sampel ini)."
            bias == "BUY" ->
                "Divergensi: sinyal BUY dominan tetapi harga ${if (trend < 0) "justru turun" else "belum naik berarti"}. " +
                        "Pembelian besar mungkin diserap penjual atau hanya pantulan dalam tren turun — hati-hati menjadikan sinyal BUY satu-satunya alasan."
            bias == "SELL" && trend < 0 ->
                "Arus jual sejalan dengan harga yang turun — konfirmasi yang cukup baik untuk sisi turun (di sampel ini)."
            bias == "SELL" ->
                "Divergensi: sinyal SELL dominan tetapi harga ${if (trend > 0) "justru naik" else "belum turun berarti"}. " +
                        "Penjualan besar mungkin diserap pembeli (akumulasi) — risiko harga berbalik naik tetap ada."
            else -> "Tanpa sisi dominan; harga ${
                when (trend) {
                    1 -> "cenderung naik"
                    -1 -> "cenderung turun"
                    else -> "cenderung mendatar"
                }
            } di rentang ini."
        }

        for (h in hits) {
            if (h.buyTotal + h.sellTotal >= 6) conclusions += hitText(h)
        }
        val h15 = hits.first()
        val tot15 = h15.buyTotal + h15.sellTotal
        if (tot15 >= 8) {
            val r = (h15.buyHits + h15.sellHits).toDouble() / tot15
            conclusions += when {
                r < 0.45 -> "Di sampel ini sinyal justru lebih sering berlawanan dengan arah harga 15 menit kemudian — jangan dipakai mentah-mentah sebagai pemicu entri."
                r < 0.55 -> "Ketepatan sinyal hampir sama dengan tebak-tebakan (±50%) di sampel ini — nilai prediktifnya belum terbukti."
                r < 0.60 -> "Sinyal sedikit lebih baik daripada acak di sampel ini, tetapi margin tipis dan sampel kecil."
                else -> "Sinyal cukup selaras dengan arah harga setelahnya di sampel ini (masih sampel kecil)."
            }
        } else {
            conclusions += "Uji ketepatan sinyal belum bisa dihitung (butuh minimal 8 sinyal yang punya harga pembanding ≥15 menit kemudian)."
        }

        // ---------------------------------------------------------------- syarat target & perkiraan rentang
        val lev = leverage.coerceAtLeast(1)
        val reqLow = 30.0 / lev
        val reqHigh = 50.0 / lev
        val liq = 100.0 / lev
        val targetInfo = ArrayList<String>()
        targetInfo += "Target ROI 30–50% pada leverage ${lev}x ≈ harga bergerak ${f2(reqLow)}–${f2(reqHigh)}% searah posisi " +
                "(belum termasuk fee dan funding)."
        targetInfo += "Ruang gerak berlawanan sebelum likuidasi kira-kira ${f2(liq)}% " +
                "(perkiraan kasar; maintenance margin dan fee membuatnya lebih sempit)."
        if (spanMin >= 30 && n >= 6 && rangePct > 0) {
            val e12 = rangePct * sqrt(12 * 60 / spanMin)
            val e18 = rangePct * sqrt(18 * 60 / spanMin)
            targetInfo += "Rentang harga terekam ${f2(rangePct)}% dalam ${spanText(spanMin)}. Diskalakan ke 12–18 jam (skala √waktu), " +
                    "rentang totalnya kira-kira ${f2(e12)}–${f2(e18)}% — angka kasar dan cenderung terlalu kecil karena harga hanya terekam saat ada sinyal."
            val ratio = reqLow / ((e12 + e18) / 2)
            targetInfo += when {
                ratio < 0.5 -> "Target ${f2(reqLow)}% tampak kecil dibanding perkiraan rentang tersebut — secara ukuran gerak masih dalam jangkauan wajar."
                ratio < 1.0 -> "Target butuh gerak searah yang cukup besar dibanding perkiraan rentang; lebih mungkin tercapai bila tren bertahan, sulit bila pasar bolak-balik."
                else -> "Target lebih besar dari perkiraan rentang — butuh gerakan lebih besar dari yang tersirat di data; terlihat berat."
            } + " (Ini hanya perbandingan ukuran gerak, bukan peluang tercapainya.)"
        } else {
            targetInfo += "Perkiraan rentang 12–18 jam belum bisa dihitung (butuh minimal 30 menit data dan 6 sinyal)."
        }

        // ---------------------------------------------------------------- skenario 12-18 jam (bersyarat)
        val tUpLow = pLast * (1 + reqLow / 100)
        val tUpHigh = pLast * (1 + reqHigh / 100)
        val tDnLow = pLast * (1 - reqLow / 100)
        val tDnHigh = pLast * (1 - reqHigh / 100)
        val levels = "Level dari log: bawah ${Fmt.price(pLow)}, atas ${Fmt.price(pHigh)}, terakhir ${Fmt.price(pLast)}."
        val dir = when {
            score >= 15 -> 1
            score <= -15 -> -1
            else -> 0
        }
        // "solid" = arah kuat DAN tidak sering berbalik. Kalau tidak solid, skenario sideways jadi yang utama.
        val solid = abs(score) >= 40 && flipRate < 0.5
        val lean = if (solid) "unggul" else "sedikit unggul dan masih lemah"
        val choppyNote = if (flipRate >= 0.5) ", sinyal sering berbalik" else ""

        val scenarios: List<Scenario> = when (dir) {
            1 -> {
                val cont = Scenario(
                    if (solid) "Utama — lanjut naik" else "Alternatif — lanjut naik (bila arus beli menguat)",
                    "Arus beli $lean (skor $score). Untuk ROI 30–50% (${lev}x) pada posisi LONG, harga perlu ke sekitar " +
                            "${Fmt.price(tUpLow)}–${Fmt.price(tUpHigh)} dari harga terakhir. " +
                            "Penguat: sinyal BUY terus muncul dan harga bertahan di atas ${Fmt.price(pLow)}. $levels"
                )
                val side = Scenario(
                    if (solid) "Alternatif — sideways" else "Utama — sideways, condong naik tipis",
                    if (solid) {
                        "Bila sinyal mulai bolak-balik atau SELL mencapai separuh sinyal terbaru, harga bisa tertahan lama. " +
                                "Posisi 12–18 jam lalu menanggung funding dan fee tanpa kemajuan berarti."
                    } else {
                        "Arah belum solid (skor $score$choppyNote): harga lebih mungkin tertahan atau bolak-balik dengan " +
                                "kecondongan naik tipis. Posisi 12–18 jam menanggung funding dan fee tanpa kemajuan berarti bila tren tidak menguat."
                    }
                )
                val rev = Scenario(
                    "Risiko — berbalik turun",
                    "Tanda: rangkaian sinyal SELL beruntun disertai harga menembus ${Fmt.price(pLow)} (terendah di log). " +
                            "Pada ${lev}x, gerak berlawanan sekitar ${f2(liq)}% dari entri sudah mendekati likuidasi."
                )
                if (solid) listOf(cont, side, rev) else listOf(side, cont, rev)
            }
            -1 -> {
                val cont = Scenario(
                    if (solid) "Utama — lanjut turun" else "Alternatif — lanjut turun (bila arus jual menguat)",
                    "Arus jual $lean (skor $score). Untuk ROI 30–50% (${lev}x) pada posisi SHORT, harga perlu ke sekitar " +
                            "${Fmt.price(tDnHigh)}–${Fmt.price(tDnLow)} dari harga terakhir. " +
                            "Penguat: sinyal SELL terus muncul dan harga bertahan di bawah ${Fmt.price(pHigh)}. $levels"
                )
                val side = Scenario(
                    if (solid) "Alternatif — sideways" else "Utama — sideways, condong turun tipis",
                    if (solid) {
                        "Bila sinyal mulai bolak-balik atau BUY mencapai separuh sinyal terbaru, harga bisa tertahan lama. " +
                                "Posisi 12–18 jam lalu menanggung funding dan fee tanpa kemajuan berarti."
                    } else {
                        "Arah belum solid (skor $score$choppyNote): harga lebih mungkin tertahan atau bolak-balik dengan " +
                                "kecondongan turun tipis. Posisi 12–18 jam menanggung funding dan fee tanpa kemajuan berarti bila tren tidak menguat."
                    }
                )
                val rev = Scenario(
                    "Risiko — berbalik naik",
                    "Tanda: rangkaian sinyal BUY beruntun disertai harga menembus ${Fmt.price(pHigh)} (tertinggi di log). " +
                            "Pada ${lev}x, gerak berlawanan sekitar ${f2(liq)}% dari entri sudah mendekati likuidasi."
                )
                if (solid) listOf(cont, side, rev) else listOf(side, cont, rev)
            }
            else -> listOf(
                Scenario(
                    "Utama — sideways / belum ada arah",
                    "Skor $score (netral): data tidak memberi keunggulan arah. Posisi yang dibuka sekarang pada dasarnya bertaruh " +
                            "pada pergerakan berikutnya, bukan pada sinyal. $levels"
                ),
                Scenario(
                    "Alternatif — breakout naik",
                    "Tanda: harga menembus ${Fmt.price(pHigh)} disertai rangkaian sinyal BUY beruntun. " +
                            "Untuk ROI 30–50% (${lev}x) dari harga terakhir butuh sekitar ${Fmt.price(tUpLow)}–${Fmt.price(tUpHigh)}."
                ),
                Scenario(
                    "Alternatif — breakdown turun",
                    "Tanda: harga menembus ${Fmt.price(pLow)} disertai rangkaian sinyal SELL beruntun. " +
                            "Untuk ROI 30–50% (${lev}x) dari harga terakhir butuh sekitar ${Fmt.price(tDnHigh)}–${Fmt.price(tDnLow)}."
                )
            )
        }

        return AnalysisResult(
            n = n, buyN = buyN, sellN = sellN,
            firstTs = first, lastTs = last, spanMin = spanMin,
            points = pts, buckets = buckets, bucketMinutes = bucketMinutes,
            priceFirst = pFirst, priceLast = pLast, priceHigh = pHigh, priceLow = pLow,
            changePct = changePct, rangePct = rangePct,
            flips = flips, flipRate = flipRate,
            longest = longest, longestIsBuy = longestIsBuy,
            current = cur, currentIsBuy = currentIsBuy,
            recentBuy = rb, recentSell = rs,
            oldBuy = ob, oldSell = os, weightedBuyPct = weightedBuyPct,
            score = score, direction = directionLabel(score), confidence = confidence,
            notes = notes, conclusions = conclusions, targetInfo = targetInfo, scenarios = scenarios
        )
    }

    // ------------------------------------------------------------------ fungsi bantu

    private fun buildBuckets(
        pts: List<Point>,
        first: Long,
        last: Long,
        spanMin: Double
    ): Pair<Int, List<Bucket>> {
        val bm = when {
            spanMin <= 180 -> 10
            spanMin <= 720 -> 30
            spanMin <= 4320 -> 60
            else -> 240
        }
        val size = bm * 60_000L
        val count = ((last - first) / size).toInt() + 1
        val buy = IntArray(count)
        val sell = IntArray(count)
        for (p in pts) {
            val i = ((p.ts - first) / size).toInt().coerceIn(0, count - 1)
            if (p.isBuy) buy[i]++ else sell[i]++
        }
        return bm to List(count) { Bucket(first + it * size, buy[it], sell[it]) }
    }

    /** Untuk tiap sinyal: bandingkan harga saat sinyal dengan harga di sinyal pertama yang muncul >= horizon kemudian. */
    private fun hitRate(pts: List<Point>, horizonMin: Int): HitRate {
        var bh = 0
        var bt = 0
        var sh = 0
        var st = 0
        val h = horizonMin * 60_000L
        for (i in pts.indices) {
            val p = pts[i]
            val j = (i + 1 until pts.size).firstOrNull { pts[it].ts - p.ts >= h } ?: continue
            val diff = pts[j].price - p.price
            if (p.isBuy) {
                bt++
                if (diff > 0) bh++
            } else {
                st++
                if (diff < 0) sh++
            }
        }
        return HitRate(horizonMin, bh, bt, sh, st)
    }

    private fun hitText(h: HitRate): String {
        val b = if (h.buyTotal > 0) "${rate(h.buyHits, h.buyTotal)}% (${h.buyHits}/${h.buyTotal})" else "—"
        val s = if (h.sellTotal > 0) "${rate(h.sellHits, h.sellTotal)}% (${h.sellHits}/${h.sellTotal})" else "—"
        return "Uji ${h.horizonMin} menit setelah sinyal: harga searah BUY $b, searah SELL $s."
    }

    private fun rate(hit: Int, total: Int): Int = if (total > 0) (hit * 100.0 / total).roundToInt() else 0

    private fun directionLabel(score: Int): String = when {
        score >= 40 -> "Condong NAIK (cukup kuat)"
        score >= 15 -> "Condong naik (lemah)"
        score > -15 -> "NETRAL / belum jelas"
        score > -40 -> "Condong turun (lemah)"
        else -> "Condong TURUN (cukup kuat)"
    }

    private fun sideText(isBuy: Boolean) = if (isBuy) "BUY" else "SELL"

    private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun signed(v: Double): String = (if (v >= 0) "+" else "") + f2(v)

    private fun spanText(min: Double): String {
        val m = min.roundToInt()
        return if (m < 60) "$m menit" else "${m / 60} jam ${m % 60} menit"
    }
}