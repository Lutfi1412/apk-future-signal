package com.example.futuressignal.ui

import com.example.futuressignal.Analysis.Point
import kotlin.math.abs
import kotlin.math.floor

/** Rentang waktu penuh grafik (sudah diberi ruang kosong di kiri-kanan supaya titik tidak mepet tepi). */
internal data class TimeDomain(val t0: Long, val t1: Long) {
    val span: Double get() = (t1 - t0).toDouble()
}

/** Jendela yang sedang terlihat: start = awal jendela (0..1 dari rentang penuh), zoom >= 1. */
internal data class ViewWindow(val start: Float, val zoom: Float)

/** Rentang waktu dan harga yang sedang digambar (skala harga menyesuaikan data yang terlihat). */
internal data class PriceView(val ws: Double, val we: Double, val lo: Double, val hi: Double)

internal object ChartMath {
    const val MAX_ZOOM = 24f

    fun clampStart(start: Float, zoom: Float): Float = start.coerceIn(0f, 1f - 1f / zoom)

    /** Zoom dengan titik fokus tetap di tempatnya. focus = posisi fokus di layar (0..1). */
    fun zoomAround(w: ViewWindow, factor: Float, focus: Float): ViewWindow {
        val nz = (w.zoom * factor).coerceIn(1f, MAX_ZOOM)
        val focusInDomain = w.start + focus / w.zoom
        return ViewWindow(clampStart(focusInDomain - focus / nz, nz), nz)
    }

    fun pan(w: ViewWindow, deltaFraction: Float): ViewWindow =
        w.copy(start = clampStart(w.start + deltaFraction, w.zoom))

    fun centerOn(w: ViewWindow, frac: Float): ViewWindow =
        w.copy(start = clampStart(frac - 0.5f / w.zoom, w.zoom))

    fun ensureVisible(w: ViewWindow, frac: Float): ViewWindow =
        if (frac < w.start || frac > w.start + 1f / w.zoom) centerOn(w, frac) else w

    fun windowStart(d: TimeDomain, w: ViewWindow): Double = d.t0 + w.start * d.span
    fun windowEnd(d: TimeDomain, w: ViewWindow): Double = windowStart(d, w) + d.span / w.zoom

    /** Skala harga mengikuti titik yang terlihat + ruang 15% atas-bawah, jadi saat di-zoom grafik ikut "meregang". */
    fun priceView(points: List<Point>, d: TimeDomain, w: ViewWindow): PriceView {
        val ws = windowStart(d, w)
        val we = windowEnd(d, w)
        val vis = points.filter { it.ts >= ws && it.ts <= we }.ifEmpty { points }
        var lo = vis.minOf { it.price }
        var hi = vis.maxOf { it.price }
        if (hi - lo < 1e-9) {
            val pad = abs(hi) * 0.001 + 1e-6
            lo -= pad
            hi += pad
        } else {
            val pad = (hi - lo) * 0.15
            lo -= pad
            hi += pad
        }
        return PriceView(ws, we, lo, hi)
    }

    /** Titik terdekat dari posisi ketuk (dalam piksel); null kalau terlalu jauh. */
    fun nearestPoint(
        points: List<Point>,
        ws: Double,
        we: Double,
        width: Float,
        tapX: Float,
        maxDist: Float
    ): Int? {
        if (points.isEmpty() || we <= ws || width <= 0f) return null
        var best = -1
        var bestD = Float.MAX_VALUE
        points.forEachIndexed { i, p ->
            val x = ((p.ts - ws) / (we - ws) * width).toFloat()
            val d = abs(x - tapX)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (best >= 0 && bestD <= maxDist) best else null
    }

    /** Indeks batang (rentang waktu) di posisi ketuk; null kalau di luar data. */
    fun bucketIndexAt(
        tapX: Float,
        width: Float,
        ws: Double,
        we: Double,
        firstStart: Long,
        bucketMs: Long,
        count: Int
    ): Int? {
        if (count == 0 || width <= 0f || bucketMs <= 0L || we <= ws) return null
        val tapTs = ws + tapX / width * (we - ws)
        val idx = floor((tapTs - firstStart) / bucketMs).toInt()
        return if (idx in 0 until count) idx else null
    }
}