package com.example.futuressignal.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "Trade" di aplikasi = key "spot" di server. */
enum class Market(val key: String, val label: String) {
    FUTURE("future", "Future"),
    SPOT("spot", "Trade")
}

data class Trade(
    val price: Double,
    val qty: Double,      // nilai dalam USDT (sama seperti kolom "Jumlah" di Binance)
    val time: String,     // HH:MM:SS WIB dari server
    val status: String,   // "BUY" / "SELL"
    val ts: Long
) {
    val isBuy: Boolean get() = status == "BUY"
}

typealias Snapshot = Map<String, Map<Market, List<Trade>>>

object SnapshotParser {
    /** Parse JSON dari server: { "BTCUSDT": { "future": [...], "spot": [...] }, "SOLUSDT": {...} } */
    fun parse(json: String): Snapshot? = try {
        val root = JSONObject(json)
        val out = HashMap<String, Map<Market, List<Trade>>>()
        for (symbol in root.keys()) {
            val obj = root.optJSONObject(symbol) ?: continue
            val markets = HashMap<Market, List<Trade>>()
            for (m in Market.values()) {
                val arr = obj.optJSONArray(m.key) ?: continue
                val list = ArrayList<Trade>(arr.length())
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    list.add(
                        Trade(
                            price = t.getDouble("price"),
                            qty = t.getDouble("qty"),
                            time = t.getString("time"),
                            status = t.getString("status"),
                            ts = t.optLong("ts")
                        )
                    )
                }
                markets[m] = list
            }
            out[symbol] = markets
        }
        out
    } catch (e: Exception) {
        null
    }
}

object SnapshotJson {
    /** Bentuk sama dengan API pribadi: { "BTCUSDT": { "future": [...], "spot": [...] } }, terbaru di depan. */
    fun encode(snapshot: Snapshot, maxPerList: Int): String {
        val root = JSONObject()
        for ((symbol, markets) in snapshot) {
            val obj = JSONObject()
            for ((market, trades) in markets) {
                val arr = JSONArray()
                for (t in trades.take(maxPerList)) {
                    arr.put(
                        JSONObject()
                            .put("price", t.price)
                            .put("qty", t.qty)
                            .put("time", t.time)
                            .put("status", t.status)
                            .put("ts", t.ts)
                    )
                }
                obj.put(market.key, arr)
            }
            root.put(symbol, obj)
        }
        return root.toString()
    }
}

object Fmt {
    private val wib: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    fun time(ms: Long): String {
        if (ms <= 0L) return "--:--:--"
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        f.timeZone = wib
        return f.format(Date(ms))
    }

    fun price(v: Double): String = String.format(Locale.US, "%,.2f", v)

    fun compact(v: Double): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "%.2fK", v / 1_000)
        else -> String.format(Locale.US, "%.2f", v)
    }
}