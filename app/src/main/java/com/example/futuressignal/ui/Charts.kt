package com.example.futuressignal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.futuressignal.Analysis.AnalysisResult
import com.example.futuressignal.Analysis.Bucket
import com.example.futuressignal.Analysis.Point
import com.example.futuressignal.data.Fmt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val GREEN = Color(0xFF0ECB81)
private val RED = Color(0xFFF6465D)

/** Status zoom/geser yang dipakai bersama oleh grafik harga dan grafik batang (sumbu waktu sinkron). */
@Stable
internal class ChartViewState {
    var window by mutableStateOf(ViewWindow(0f, 1f))
        private set

    val zoom: Float get() = window.zoom

    fun zoomAround(factor: Float, focus: Float) {
        window = ChartMath.zoomAround(window, factor, focus)
    }

    fun panByFraction(delta: Float) {
        window = ChartMath.pan(window, delta)
    }

    fun ensureVisible(frac: Float) {
        window = ChartMath.ensureVisible(window, frac)
    }

    fun reset() {
        window = ViewWindow(0f, 1f)
    }
}

/**
 * Gestur grafik:
 *  - cubit dua jari = zoom (di sekitar titik cubitan) + geser
 *  - geser satu jari = menggulung grafik, HANYA saat sedang di-zoom dan arahnya mendatar
 *  Saat tidak di-zoom, geser satu jari dibiarkan ke halaman/daftar di belakangnya (pindah tab, scroll).
 */
private fun Modifier.chartTransform(state: ChartViewState): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var decided = false
        var panning = false
        var totalDx = 0f
        var totalDy = 0f
        do {
            val event = awaitPointerEvent()
            val w = size.width.toFloat()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                val z = event.calculateZoom()
                val p = event.calculatePan()
                val c = event.calculateCentroid()
                if (z != 1f || p != Offset.Zero) {
                    state.zoomAround(z, (c.x / w).coerceIn(0f, 1f))
                    state.panByFraction(-p.x / (w * state.zoom))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                decided = true
                panning = true
            } else if (state.zoom > 1f) {
                val p = event.calculatePan()
                if (!decided) {
                    totalDx += p.x
                    totalDy += p.y
                    val slop = viewConfiguration.touchSlop
                    if (abs(totalDx) > slop || abs(totalDy) > slop) {
                        decided = true
                        panning = abs(totalDx) > abs(totalDy)
                        if (panning) {
                            state.panByFraction(-totalDx / (w * state.zoom))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                } else if (panning) {
                    state.panByFraction(-p.x / (w * state.zoom))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

// =====================================================================================
// KARTU GRAFIK: kontrol zoom + panel detail + grafik harga + grafik batang
// =====================================================================================
@Composable
fun ChartsCard(r: AnalysisResult) {
    val view = remember { ChartViewState() }
    var selPoint by remember { mutableStateOf<Int?>(null) }
    var selBucket by remember { mutableStateOf<Int?>(null) }

    // ruang kosong 6% di kiri-kanan supaya titik pertama/terakhir tidak mepet tepi
    val span = (r.lastTs - r.firstTs).coerceAtLeast(60_000L)
    val pad = (span * 0.06).toLong().coerceAtLeast(30_000L)
    val domain = TimeDomain(r.firstTs - pad, r.lastTs + pad)
    val bucketMs = r.bucketMinutes * 60_000L

    val pIdx = selPoint?.takeIf { it in r.points.indices }
    val bIdx = selBucket?.takeIf { it in r.buckets.indices }
    val ws = ChartMath.windowStart(domain, view.window)
    val we = ChartMath.windowEnd(domain, view.window)

    val stepPoint: (Int) -> Unit = { d ->
        val cur = pIdx
        if (cur != null) {
            val ni = (cur + d).coerceIn(0, r.points.lastIndex)
            selPoint = ni
            view.ensureVisible(((r.points[ni].ts - domain.t0) / domain.span).toFloat())
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Grafik interaktif", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Cubit dua jari untuk zoom, geser untuk menggulung saat sedang di-zoom, ketuk titik atau batang untuk detail. " +
                        "Tombol di bawah juga bisa dipakai.",
                style = MaterialTheme.typography.bodySmall
            )

            // kontrol zoom dan geser
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tight = PaddingValues(horizontal = 4.dp)
                OutlinedButton(
                    onClick = { view.zoomAround(1f / 1.6f, 0.5f) },
                    enabled = view.zoom > 1f,
                    contentPadding = tight,
                    modifier = Modifier.weight(1f)
                ) { Text("−") }
                OutlinedButton(
                    onClick = { view.zoomAround(1.6f, 0.5f) },
                    enabled = view.zoom < ChartMath.MAX_ZOOM,
                    contentPadding = tight,
                    modifier = Modifier.weight(1f)
                ) { Text("+") }
                OutlinedButton(
                    onClick = { view.panByFraction(-0.5f / view.zoom) },
                    enabled = view.zoom > 1f,
                    contentPadding = tight,
                    modifier = Modifier.weight(1f)
                ) { Text("‹") }
                OutlinedButton(
                    onClick = { view.panByFraction(0.5f / view.zoom) },
                    enabled = view.zoom > 1f,
                    contentPadding = tight,
                    modifier = Modifier.weight(1f)
                ) { Text("›") }
                OutlinedButton(
                    onClick = { view.reset() },
                    enabled = view.zoom > 1f,
                    contentPadding = tight,
                    modifier = Modifier.weight(1.4f)
                ) { Text("Reset", maxLines = 1) }
            }
            Text(
                "Zoom ${String.format(Locale.US, "%.1f", view.zoom)}×",
                style = MaterialTheme.typography.labelMedium
            )

            // ------------------------------------------------ grafik harga
            Text("Harga & sinyal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { stepPoint(-1) }, enabled = pIdx != null && pIdx > 0) { Text("‹") }
                Column(modifier = Modifier.weight(1f)) {
                    val p = pIdx?.let { r.points[it] }
                    if (p == null) {
                        Text(
                            "Ketuk titik pada grafik untuk melihat detail sinyal.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "${Fmt.time(p.ts)} • ${if (p.isBuy) "BUY" else "SELL"} • ${Fmt.price(p.price)} • dominasi ${p.sharePct}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (p.isBuy) GREEN else RED
                        )
                        val prev = if (pIdx > 0) r.points[pIdx - 1] else null
                        if (prev != null) {
                            val d = (p.price - prev.price) / prev.price * 100
                            val gapMin = ((p.ts - prev.ts) / 60_000.0).toInt()
                            Text(
                                "Δ ${if (d >= 0) "+" else ""}${String.format(Locale.US, "%.3f", d)}% dari sinyal sebelumnya ($gapMin menit sebelumnya)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                TextButton(onClick = { stepPoint(1) }, enabled = pIdx != null && pIdx < r.points.lastIndex) { Text("›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("● BUY", color = GREEN, style = MaterialTheme.typography.labelSmall)
                Text("● SELL", color = RED, style = MaterialTheme.typography.labelSmall)
            }
            if (r.points.size < 2) {
                Text("Butuh minimal 2 sinyal untuk grafik harga.", style = MaterialTheme.typography.bodySmall)
            } else {
                PriceChart(r.points, domain, view, pIdx) { selPoint = it }
                TimeAxis(ws, we)
            }

            // ------------------------------------------------ grafik batang
            Text(
                "Sinyal per ${r.bucketMinutes} menit (BUY ke atas, SELL ke bawah)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val b = bIdx?.let { r.buckets[it] }
            Text(
                if (b == null) "Ketuk batang untuk melihat jumlah sinyal pada rentang itu."
                else "${Fmt.time(b.startTs)}–${Fmt.time(b.startTs + bucketMs)} • BUY ${b.buy} • SELL ${b.sell}",
                style = if (b == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (b == null) FontWeight.Normal else FontWeight.Bold
            )
            BucketChart(r.buckets, bucketMs, domain, view, bIdx) { selBucket = it }
            TimeAxis(ws, we)
        }
    }
}

// =====================================================================================
// GRAFIK HARGA
// =====================================================================================
@Composable
private fun PriceChart(
    points: List<Point>,
    domain: TimeDomain,
    view: ChartViewState,
    selected: Int?,
    onSelect: (Int?) -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val crossColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val labelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)

    // skala harga mengikuti titik yang terlihat, jadi saat di-zoom grafik ikut meregang
    val pv = ChartMath.priceView(points, domain, view.window)
    val pvRef = rememberUpdatedState(pv)
    val pointsRef = rememberUpdatedState(points)
    val onSelectRef = rememberUpdatedState(onSelect)

    Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .chartTransform(view)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { pos ->
                        val v = pvRef.value
                        onSelectRef.value(
                            ChartMath.nearestPoint(
                                pointsRef.value, v.ws, v.we, size.width.toFloat(), pos.x, 32.dp.toPx()
                            )
                        )
                    })
                }
        ) {
            val w = size.width
            val h = size.height
            val spanMs = pv.we - pv.ws
            val range = pv.hi - pv.lo
            fun px(t: Long): Float = ((t - pv.ws) / spanMs * w).toFloat()
            fun py(p: Double): Float = h - ((p - pv.lo) / range).toFloat() * h

            for (k in 0..2) {
                val yy = h * k / 2f
                drawLine(gridColor, Offset(0f, yy), Offset(w, yy), strokeWidth = 1.dp.toPx())
            }

            clipRect {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = px(p.ts)
                    val y = py(p.price)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))

                points.forEachIndexed { i, p ->
                    drawCircle(
                        color = if (p.isBuy) GREEN else RED,
                        radius = if (i == selected) 7.dp.toPx() else 4.dp.toPx(),
                        center = Offset(px(p.ts), py(p.price))
                    )
                }

                if (selected != null && selected in points.indices) {
                    val sp = points[selected]
                    val sx = px(sp.ts)
                    val sy = py(sp.price)
                    drawLine(
                        crossColor, Offset(sx, 0f), Offset(sx, h),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                    drawCircle(crossColor, radius = 10.dp.toPx(), center = Offset(sx, sy), style = Stroke(width = 2.dp.toPx()))
                }
            }
        }

        // label sumbu harga (mengikuti skala yang sedang terlihat)
        Column(
            modifier = Modifier.fillMaxHeight().padding(start = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AxisLabel(Fmt.price(pv.hi), labelBg)
            AxisLabel(Fmt.price((pv.hi + pv.lo) / 2), labelBg)
            AxisLabel(Fmt.price(pv.lo), labelBg)
        }
    }
}

// =====================================================================================
// GRAFIK BATANG: BUY naik dari garis tengah (hijau), SELL turun (merah)
// =====================================================================================
@Composable
private fun BucketChart(
    buckets: List<Bucket>,
    bucketMs: Long,
    domain: TimeDomain,
    view: ChartViewState,
    selected: Int?,
    onSelect: (Int?) -> Unit
) {
    val axis = MaterialTheme.colorScheme.outline
    val crossColor = MaterialTheme.colorScheme.onSurface
    val labelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)

    val ws = ChartMath.windowStart(domain, view.window)
    val we = ChartMath.windowEnd(domain, view.window)
    // skala tinggi batang mengikuti batang yang terlihat
    val maxC = (buckets
        .filter { it.startTs + bucketMs >= ws && it.startTs <= we }
        .maxOfOrNull { max(it.buy, it.sell) } ?: 1).coerceAtLeast(1)

    val bucketsRef = rememberUpdatedState(buckets)
    val wsRef = rememberUpdatedState(ws)
    val weRef = rememberUpdatedState(we)
    val onSelectRef = rememberUpdatedState(onSelect)

    Box(modifier = Modifier.fillMaxWidth().height(170.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .chartTransform(view)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { pos ->
                        val list = bucketsRef.value
                        onSelectRef.value(
                            if (list.isEmpty()) null
                            else ChartMath.bucketIndexAt(
                                pos.x, size.width.toFloat(), wsRef.value, weRef.value,
                                list.first().startTs, bucketMs, list.size
                            )
                        )
                    })
                }
        ) {
            val w = size.width
            val h = size.height
            val spanMs = we - ws
            val mid = h / 2f
            val usable = mid - 2.dp.toPx()
            val fullW = (bucketMs / spanMs * w).toFloat()
            val barW = fullW * 0.78f

            clipRect {
                buckets.forEachIndexed { i, b ->
                    val left = ((b.startTs - ws) / spanMs * w).toFloat()
                    if (left + fullW < 0f || left > w) return@forEachIndexed
                    if (i == selected) {
                        drawRect(
                            crossColor.copy(alpha = 0.12f),
                            topLeft = Offset(left, 0f),
                            size = Size(fullW, h)
                        )
                    }
                    val bx = left + (fullW - barW) / 2f
                    val hb = b.buy.toFloat() / maxC * usable
                    val hs = b.sell.toFloat() / maxC * usable
                    if (hb > 0f) drawRect(GREEN, topLeft = Offset(bx, mid - hb), size = Size(barW, hb))
                    if (hs > 0f) drawRect(RED, topLeft = Offset(bx, mid), size = Size(barW, hs))
                }
                drawLine(axis, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())
            }
        }

        Column(
            modifier = Modifier.fillMaxHeight().padding(start = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AxisLabel("BUY maks $maxC", labelBg, GREEN)
            AxisLabel("SELL maks $maxC", labelBg, RED)
        }
    }
}

// ------------------------------------------------------------------ komponen kecil

@Composable
private fun AxisLabel(text: String, bg: Color, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.background(bg)
    )
}

@Composable
private fun TimeAxis(ws: Double, we: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(Fmt.time(ws.toLong()), style = MaterialTheme.typography.labelSmall)
        Text(Fmt.time(((ws + we) / 2).toLong()), style = MaterialTheme.typography.labelSmall)
        Text(Fmt.time(we.toLong()), style = MaterialTheme.typography.labelSmall)
    }
}