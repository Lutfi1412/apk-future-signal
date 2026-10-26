package com.example.futuressignal.data

/** Selang waktu setelah sinyal saat harga dicatat sebagai "hasil" (label untuk model). */
enum class Horizon(val ms: Long) {
    M5(5 * 60_000L),
    M15(15 * 60_000L),
    H1(60 * 60_000L),
    H4(4 * 60 * 60_000L),
    H12(12 * 60 * 60_000L)
}

/**
 * Pencatat harga setelah sinyal. Dijalankan layanan secara berkala: untuk tiap sinyal yang sudah
 * lewat 5 menit / 15 menit / 1 jam / 4 jam / 12 jam, harga terkini koin itu dicatat.
 * Kalau terlambat lebih dari TOLERANCE_MS (mis. layanan sedang mati), harga TIDAK dicatat (dibiarkan kosong),
 * supaya label tidak tercemar harga yang salah waktu.
 */
object Outcomes {
    const val TOLERANCE_MS = 5 * 60_000L

    fun get(o: Outcome, h: Horizon): Double = when (h) {
        Horizon.M5 -> o.px5m
        Horizon.M15 -> o.px15m
        Horizon.H1 -> o.px1h
        Horizon.H4 -> o.px4h
        Horizon.H12 -> o.px12h
    }

    fun with(o: Outcome, h: Horizon, px: Double): Outcome = when (h) {
        Horizon.M5 -> o.copy(px5m = px)
        Horizon.M15 -> o.copy(px15m = px)
        Horizon.H1 -> o.copy(px1h = px)
        Horizon.H4 -> o.copy(px4h = px)
        Horizon.H12 -> o.copy(px12h = px)
    }

    /** Gabungkan: nilai yang sudah ada tidak ditimpa. */
    fun merge(base: Outcome, patch: Outcome): Outcome = Outcome(
        px5m = if (base.px5m == 0.0) patch.px5m else base.px5m,
        px15m = if (base.px15m == 0.0) patch.px15m else base.px15m,
        px1h = if (base.px1h == 0.0) patch.px1h else base.px1h,
        px4h = if (base.px4h == 0.0) patch.px4h else base.px4h,
        px12h = if (base.px12h == 0.0) patch.px12h else base.px12h
    )

    /**
     * entries: terbaru di depan. priceOf(simbol, labelPasar) = harga terkini atau null.
     * Mengembalikan patch (id log -> harga yang baru dicatat).
     */
    fun findDue(entries: List<LogEntry>, now: Long, priceOf: (String, String) -> Double?): Map<Long, Outcome> {
        val maxAge = Horizon.H12.ms + TOLERANCE_MS
        val patches = HashMap<Long, Outcome>()
        for (e in entries) {
            val age = now - e.ts
            if (age > maxAge) break                       // daftar terbaru di depan: sisanya lebih tua lagi
            if (e.type != LogType.SIGNAL || e.symbol.isEmpty()) continue
            var patch: Outcome? = null
            for (h in Horizon.values()) {
                if (get(e.outcome, h) != 0.0) continue    // sudah tercatat
                val late = age - h.ms
                if (late < 0 || late > TOLERANCE_MS) continue
                val px = priceOf(e.symbol, e.market) ?: continue
                patch = with(patch ?: Outcome(), h, px)
            }
            if (patch != null) patches[e.id] = patch
        }
        return patches
    }
}