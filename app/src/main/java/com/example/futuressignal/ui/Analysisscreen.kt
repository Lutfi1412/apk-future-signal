package com.example.futuressignal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.futuressignal.Analysis.AnalysisResult
import com.example.futuressignal.Analysis.Analyzer
import com.example.futuressignal.Analysis.Scenario
import com.example.futuressignal.data.FeedRules
import com.example.futuressignal.data.Fmt
import com.example.futuressignal.data.LogEntry
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.LogType
import com.example.futuressignal.data.Prefs
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val GREEN = Color(0xFF0ECB81)
private val RED = Color(0xFFF6465D)

private data class AnalysisTab(val label: String, val symbol: String, val market: String)

/**
 * Tab = tiap koin x pasar. Empat tab bawaan (BTC dan SOL x Trade dan Future) selalu ada;
 * koin lain muncul bila diatur (Binance langsung) atau sudah punya log.
 */
private fun buildTabs(entries: List<LogEntry>, configured: List<String>): List<AnalysisTab> {
    val symbols = (
            FeedRules.DEFAULT_SYMBOLS + configured +
                    entries.filter { it.type == LogType.SIGNAL && it.symbol.isNotEmpty() }.map { it.symbol }
            ).distinct()
    return listOf("Trade", "Future").flatMap { market ->
        symbols.map { AnalysisTab("$market ${it.removeSuffix("USDT")}", it, market) }
    }
}

private val LEVERAGES = listOf(5, 10, 20, 25)

private fun spanLabel(min: Double): String {
    val m = min.roundToInt()
    return if (m < 60) "$m menit" else "${m / 60} jam ${m % 60} menit"
}

// =====================================================================================
// HALAMAN ANALISIS: 4 halaman (geser ke samping atau ketuk tab di atas)
// =====================================================================================
@Composable
fun AnalysisScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries by LogStore.entries.collectAsState()
    var leverage by remember { mutableStateOf(Prefs.leverage(ctx)) }
    val tabs = remember(entries) { buildTabs(entries, Prefs.watchSymbols(ctx)) }
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                TextButton(onClick = onBack) { Text("‹ Kembali") }
                Text(
                    "Analisis",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tab (bisa diketuk, dan bisa digulung bila koinnya banyak) — halaman juga bisa digeser ke samping
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { i, t ->
                    val selected = pager.currentPage == i
                    if (selected) {
                        Button(
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.widthIn(min = 88.dp)
                        ) { Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.widthIn(min = 88.dp)
                        ) { Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    }
                }
            }

            // Pilihan leverage (dipakai untuk menghitung target ROI 30-50%)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Leverage:", style = MaterialTheme.typography.labelMedium)
                LEVERAGES.forEach { l ->
                    if (l == leverage) {
                        Button(
                            onClick = {},
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) { Text("${l}x") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                leverage = l
                                Prefs.setLeverage(ctx, l)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) { Text("${l}x") }
                    }
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                tabs.getOrNull(page)?.let { AnalysisPage(entries, it, leverage) }
            }
        }
    }
}

@Composable
private fun AnalysisPage(entries: List<LogEntry>, tab: AnalysisTab, leverage: Int) {
    val result = remember(entries, tab, leverage) {
        Analyzer.analyze(entries, tab.symbol, tab.market, leverage, System.currentTimeMillis())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (result == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Belum ada sinyal ${tab.label} di log. Nyalakan layanan dan biarkan berjalan; " +
                                "analisis memakai log yang belum dihapus.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            item { SummaryCard(tab, result) }

            if (result.notes.isNotEmpty()) {
                item { TextListCard("Catatan kualitas data", result.notes) }
            }

            item { ChartsCard(result) }

            item { StatsCard(result) }
            item { TextListCard("Kesimpulan dari data log", result.conclusions) }
            item { TextListCard("Target & rentang harga (leverage ${leverage}x)", result.targetInfo) }

            item {
                Text(
                    "Skenario 12–18 jam ke depan (bersyarat)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(result.scenarios) { s -> ScenarioCard(s) }

            item {
                Text(
                    "Analisis ini membaca aliran order dari sinyal di log (bukan prediksi harga) dan hanya memakai data yang Anda " +
                            "kumpulkan. Data 1–2 jam tidak cukup untuk memastikan arah 12–18 jam; skenario di atas adalah hipotesis " +
                            "bersyarat, bukan jaminan. Gunakan sebagai tambahan konteks, bukan satu-satunya dasar keputusan. Bukan saran keuangan.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ------------------------------------------------------------------ kartu-kartu

@Composable
private fun SummaryCard(tab: AnalysisTab, r: AnalysisResult) {
    val dirColor = when {
        r.score >= 15 -> GREEN
        r.score <= -15 -> RED
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tab.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                r.direction,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = dirColor
            )
            ScoreGauge(r.score)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("TURUN −100", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Skor ${if (r.score > 0) "+" else ""}${r.score}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("+100 NAIK", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Keyakinan data: ${r.confidence.label} • ${r.n} sinyal • ${spanLabel(r.spanMin)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Berbobot waktu: BUY ${r.weightedBuyPct}% vs SELL ${100 - r.weightedBuyPct}% (sinyal baru lebih berpengaruh)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Data: ${Fmt.time(r.firstTs)} – ${Fmt.time(r.lastTs)} WIB",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatsCard(r: AnalysisResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Statistik", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("BUY", "${r.buyN}", GREEN, Modifier.weight(1f))
                Stat("SELL", "${r.sellN}", RED, Modifier.weight(1f))
                Stat("Berbalik", "${r.flips}× (${(r.flipRate * 100).roundToInt()}%)", Color.Unspecified, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("Lama", "${r.oldBuy}B / ${r.oldSell}S", Color.Unspecified, Modifier.weight(1f))
                Stat("Baru (30 mnt)", "${r.recentBuy}B / ${r.recentSell}S", Color.Unspecified, Modifier.weight(1f))
                Stat(
                    "Saat ini",
                    "${r.current}× ${if (r.currentIsBuy) "BUY" else "SELL"}",
                    if (r.currentIsBuy) GREEN else RED,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun TextListCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ScenarioCard(s: Scenario) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(s.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Meteran skor: tengah = netral, ke kanan = condong naik (hijau), ke kiri = condong turun (merah). */
@Composable
private fun ScoreGauge(score: Int) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val centerColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(track, size = Size(w, h), cornerRadius = CornerRadius(h / 2f, h / 2f))
        val mid = w / 2f
        val pos = (score.coerceIn(-100, 100) + 100) / 200f * w
        val color = if (score >= 0) GREEN else RED
        drawRect(color, topLeft = Offset(min(mid, pos), 0f), size = Size(abs(pos - mid), h))
        drawLine(centerColor, Offset(mid, 0f), Offset(mid, h), strokeWidth = 2.dp.toPx())
    }
}