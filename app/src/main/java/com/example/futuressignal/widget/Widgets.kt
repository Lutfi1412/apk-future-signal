package com.example.futuressignal.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.example.futuressignal.data.Market

// ---- 4 widget ----
// Nama kelas dipertahankan (didaftarkan di AndroidManifest.xml). Isinya mengikuti urutan koin:
// "Btc" = koin pertama (bawaan BTC), "Sol" = koin kedua (bawaan SOL).
class FutureBtcWidget : TradeListWidget(0, Market.FUTURE)
class FutureSolWidget : TradeListWidget(1, Market.FUTURE)
class TradeBtcWidget : TradeListWidget(0, Market.SPOT)
class TradeSolWidget : TradeListWidget(1, Market.SPOT)

// ---- 4 receiver (didaftarkan di AndroidManifest.xml) ----
class FutureBtcReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FutureBtcWidget()
}

class FutureSolReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FutureSolWidget()
}

class TradeBtcReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TradeBtcWidget()
}

class TradeSolReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TradeSolWidget()
}