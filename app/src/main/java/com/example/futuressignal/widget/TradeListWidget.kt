package com.example.futuressignal.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.futuressignal.MainActivity
import com.example.futuressignal.data.FeedRules
import com.example.futuressignal.data.Fmt
import com.example.futuressignal.data.Market
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.Snapshot
import com.example.futuressignal.data.SnapshotStore
import com.example.futuressignal.data.Source
import com.example.futuressignal.data.Trade

@SuppressLint("RestrictedApi")
private val GREEN = ColorProvider(Color(0xFF0ECB81))
@SuppressLint("RestrictedApi")
private val RED = ColorProvider(Color(0xFFF6465D))
@SuppressLint("RestrictedApi")
private val WHITE = ColorProvider(Color(0xFFEAECEF))
@SuppressLint("RestrictedApi")
private val GRAY = ColorProvider(Color(0xFF848E9C))
@SuppressLint("RestrictedApi")
private val BG = ColorProvider(Color(0xFF0B0E11))

/**
 * Satu kelas dasar untuk 4 widget. slot 0 = koin pertama, slot 1 = koin kedua
 * (bawaan BTC dan SOL; mengikuti daftar koin bila memakai Binance langsung).
 */
abstract class TradeListWidget(
    private val slot: Int,
    private val market: Market
) : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = SnapshotStore.load(context)
        val symbol = symbolForSlot(context, slot, snap)
        val trades = symbol?.let { snap?.get(it)?.get(market) }.orEmpty().take(MAX_ROWS)
        val title = "${market.label.uppercase()} • ${symbol?.removeSuffix("USDT") ?: "—"}"
        val live = Prefs.isLive(context)
        val updated = SnapshotStore.updatedAt

        provideContent {
            WidgetContent(title, trades, live, updated)
        }
    }
}

/** Batas baris yang digambar di widget (limit transaksi besar tidak membebani widget). */
private const val MAX_ROWS = 100

private fun symbolForSlot(context: Context, slot: Int, snap: Snapshot?): String? {
    val list = if (Prefs.source(context) == Source.BINANCE) {
        Prefs.binanceConfig(context).symbols
    } else {
        snap?.keys?.sorted()?.takeIf { it.isNotEmpty() } ?: FeedRules.DEFAULT_SYMBOLS
    }
    return list.getOrNull(slot)
}

@Composable
private fun WidgetContent(title: String, trades: List<Trade>, live: Boolean, updated: Long) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BG)
            .cornerRadius(16.dp)
            .padding(10.dp)
    ) {
        // Header: judul + status (ketuk untuk buka aplikasi)
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = TextStyle(color = WHITE, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = (if (live) "● LIVE " else "○ OFF ") + Fmt.time(updated),
                style = TextStyle(
                    color = if (live) GREEN else RED,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        // Judul kolom
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text("Harga", style = TextStyle(color = GRAY, fontSize = 10.sp), modifier = GlanceModifier.defaultWeight())
            Text(
                "Jumlah (USDT)",
                style = TextStyle(color = GRAY, fontSize = 10.sp, textAlign = TextAlign.End),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                "Waktu",
                style = TextStyle(color = GRAY, fontSize = 10.sp, textAlign = TextAlign.End),
                modifier = GlanceModifier.defaultWeight()
            )
        }

        Spacer(modifier = GlanceModifier.height(2.dp))

        if (trades.isEmpty()) {
            Text("Menunggu data…", style = TextStyle(color = GRAY, fontSize = 12.sp))
        } else {
            // LazyColumn = bisa di-scroll di home screen
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(trades) { t -> TradeRow(t) }
            }
        }
    }
}

@Composable
private fun TradeRow(t: Trade) {
    val color = if (t.isBuy) GREEN else RED
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            Fmt.price(t.price),
            style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.defaultWeight()
        )
        Text(
            Fmt.compact(t.qty),
            style = TextStyle(color = WHITE, fontSize = 12.sp, textAlign = TextAlign.End),
            modifier = GlanceModifier.defaultWeight()
        )
        Text(
            t.time,
            style = TextStyle(color = WHITE, fontSize = 12.sp, textAlign = TextAlign.End),
            modifier = GlanceModifier.defaultWeight()
        )
    }
}