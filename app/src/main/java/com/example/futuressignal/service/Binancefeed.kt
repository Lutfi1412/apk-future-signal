package com.example.futuressignal.service

import com.example.futuressignal.data.Bar
import com.example.futuressignal.data.BarAggregator
import com.example.futuressignal.data.BinanceConfig
import com.example.futuressignal.data.FeedLogic
import com.example.futuressignal.data.FeedStatus
import com.example.futuressignal.data.Market
import com.example.futuressignal.data.PairBuffer
import com.example.futuressignal.data.Snapshot
import com.example.futuressignal.data.Trade
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Sumber data "Binance langsung". Meniru server pribadi (binance.ts + store.ts), tetapi berjalan di aplikasi:
 *  - SATU WebSocket per koin per pasar (bawaan BTC + SOL x Trade + Future = 4 koneksi)
 *  - isi awal dari REST (limit transaksi), lalu transaksi baru dari WebSocket
 *  - daftar per pasangan: terbaru di depan, dipotong sesuai limit
 *  - snapshot dikirim tiap BROADCAST_MS (200 ms) bila ada perubahan, bentuk datanya sama dengan API pribadi:
 *      { "BTCUSDT": { "future": [ {price, qty(USDT), time(WIB), status, ts} ... ], "spot": [ ... ] }, ... }
 */
class BinanceFeed(
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val config: BinanceConfig,
    private val onSnapshot: (Snapshot) -> Unit,
    private val onStatus: (FeedStatus) -> Unit,
    private val onBar: ((String, Market, Bar) -> Unit)? = null     // bar menit dari transaksi mentah (untuk data model)
) {
    private val wsClient = client
    private val restClient = client.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private class PairState(val symbol: String, val market: Market, limit: Int) {
        val buffer = PairBuffer(limit)
        val bars = BarAggregator()
        @Volatile var connected = false
        @Volatile var error: String? = null
        @Volatile var socket: WebSocket? = null
    }

    private sealed class Seed {
        class Ok(val trades: List<Trade>) : Seed()
        class Fatal(val message: String) : Seed()                       // mis. simbol tidak ada: berhenti
        class Retry(val message: String, val minDelayMs: Long = 0L) : Seed()
    }

    private val pairs: List<PairState> = buildList {
        for (s in config.symbols) {
            if (config.spot) add(PairState(s, Market.SPOT, config.limit))
            if (config.future) add(PairState(s, Market.FUTURE, config.limit))
        }
    }

    private val jobs = ArrayList<Job>()
    @Volatile private var dirty = false
    @Volatile private var stopped = false

    fun start() {
        for (p in pairs) jobs += scope.launch(Dispatchers.IO) { runPair(p) }
        jobs += scope.launch {
            while (isActive) {
                delay(BROADCAST_MS)
                if (dirty && !stopped) {
                    dirty = false
                    onSnapshot(buildSnapshot())
                }
            }
        }
        report()
    }

    fun stop() {
        stopped = true
        jobs.forEach { it.cancel() }
        pairs.forEach { it.socket?.cancel() }
    }

    // ------------------------------------------------------------------ satu pasangan (koin + pasar)

    private suspend fun runPair(p: PairState) {
        var attempt = 0
        while (!stopped) {
            p.buffer.reset()
            p.bars.reset()                 // menit pertama setelah (re)connect tidak utuh -> dibuang oleh agregator
            p.error = null
            val closed = CompletableDeferred<Unit>()
            var retryDelay = 0L
            var openedAt = 0L

            // 1) buka WebSocket lebih dulu supaya transaksi selama isi awal tidak hilang
            val ws = wsClient.newWebSocket(
                Request.Builder().url(FeedLogic.streamUrl(p.symbol, p.market)).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        openedAt = System.currentTimeMillis()
                        p.connected = true
                        report()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handle(p, text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        p.error = if (response != null && (response.code == 451 || response.code == 403)) {
                            "diblokir dari jaringan ini (HTTP ${response.code})"
                        } else {
                            t.message ?: "koneksi gagal"
                        }
                        closed.complete(Unit)
                    }
                }
            )
            p.socket = ws

            try {
                // 2) isi awal dari REST (sekaligus memeriksa apakah simbolnya ada)
                when (val seed = withContext(Dispatchers.IO) { fetchSeed(p) }) {
                    is Seed.Ok -> {
                        p.buffer.seed(seed.trades)
                        p.error = null
                        dirty = true
                        report()
                        closed.await()                 // 3) tunggu sampai koneksi terputus
                    }
                    is Seed.Fatal -> {
                        p.error = seed.message
                        p.connected = false
                        ws.cancel()
                        report()
                        return                          // tidak dicoba lagi
                    }
                    is Seed.Retry -> {
                        p.error = seed.message
                        retryDelay = seed.minDelayMs
                        ws.cancel()
                    }
                }
            } finally {
                ws.cancel()
                p.connected = false
                report()
            }

            // Jeda sambung ulang bertambah (1, 2, 4 ... 30 detik) dan hanya kembali ke awal bila koneksi sempat
            // bertahan > 30 detik. Tanpa ini, WebSocket yang selalu gagal akan memanggil REST tiap detik
            // dan bisa kena batas permintaan Binance.
            val stable = openedAt > 0L && System.currentTimeMillis() - openedAt > 30_000L
            attempt = if (stable) 0 else attempt + 1
            val wait = maxOf(min(1000L shl min(attempt, 5), 30_000L), retryDelay)
            delay(wait)
        }
    }

    /** Transaksi baru dari WebSocket (stream tunggal: payload langsung, bukan dibungkus). */
    private fun handle(p: PairState, text: String) {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val t = FeedLogic.fromStream(
            price = o.optString("p"),
            qty = o.optString("q"),
            ts = o.optLong("T"),
            buyerIsMaker = o.optBoolean("m")
        ) ?: return
        p.buffer.push(t)
        onBar?.let { sink -> p.bars.add(t).forEach { sink(p.symbol, p.market, it) } }
        dirty = true
    }

    private fun fetchSeed(p: PairState): Seed {
        val url = FeedLogic.restUrl(p.symbol, p.market, config.limit)
        return try {
            restClient.newCall(Request.Builder().url(url).build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                when {
                    res.isSuccessful -> {
                        val arr = JSONArray(body)
                        val list = ArrayList<Trade>(arr.length())
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            FeedLogic.fromRest(
                                price = r.optString("price"),
                                quoteQty = r.optString("quoteQty"),
                                ts = r.optLong("time"),
                                buyerIsMaker = r.optBoolean("isBuyerMaker")
                            )?.let { list.add(it) }
                        }
                        list.reverse()                  // REST: terlama -> terbaru; daftar kita terbaru di depan
                        Seed.Ok(list)
                    }
                    res.code == 400 -> Seed.Fatal("simbol tidak ditemukan di pasar ini")
                    res.code == 451 || res.code == 403 -> Seed.Retry("diblokir dari jaringan ini (HTTP ${res.code})")
                    res.code == 418 || res.code == 429 -> Seed.Retry("kena batas permintaan Binance (HTTP ${res.code})", 60_000L)
                    else -> Seed.Retry("HTTP ${res.code}")
                }
            }
        } catch (e: Exception) {
            Seed.Retry(e.message ?: "gagal terhubung")
        }
    }

    // ------------------------------------------------------------------ snapshot & status

    /** Bentuk sama dengan API pribadi: simbol -> pasar -> daftar transaksi (terbaru di depan). */
    private fun buildSnapshot(): Snapshot {
        val out = LinkedHashMap<String, MutableMap<Market, List<Trade>>>()
        for (p in pairs) out.getOrPut(p.symbol) { HashMap() }[p.market] = p.buffer.snapshot()
        return out
    }

    @Synchronized
    private fun report() {
        if (stopped) return          // setelah dihentikan (ganti sumber / layanan mati) jangan lapor lagi
        val errors = pairs.mapNotNull { p -> p.error?.let { "${p.symbol} ${p.market.label}: $it" } }
        onStatus(FeedStatus(pairs.count { it.connected }, pairs.size, errors))
    }

    private companion object {
        /** Sama dengan BROADCAST_MS di server pribadi: menjaga arti "berturut-turut" tetap sama. */
        const val BROADCAST_MS = 200L
    }
}