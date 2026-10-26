package com.example.futuressignal.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LogType { SIGNAL, SYSTEM }

/** Satu baris log aktivitas. SIGNAL = sinyal trading, SYSTEM = layanan ON/OFF dan koneksi. */
data class LogEntry(
    val id: Long,
    val ts: Long,
    val type: LogType,
    val title: String,
    val message: String,
    val symbol: String = "",
    val market: String = "",
    val side: String = "",
    val buy: Double = 0.0,
    val sell: Double = 0.0,
    val sharePct: Int = 0,
    val streak: Int = 0,
    val trades: List<Trade> = emptyList(),   // seluruh transaksi di daftar saat sinyal muncul
    val seen: Boolean = false,               // false = belum pernah dibuka (penanda "baru")
    val thrPct: Double = 0.0,                // ambang minimum unggul (%) yang berlaku saat sinyal (0 = tidak dicatat)
    val stats: WindowStats? = null,          // ringkasan jendela transaksi (tetap ada walau daftar transaksi diringkas)
    val outcome: Outcome = Outcome()         // harga setelah 5m/15m/1j/4j/12j (diisi otomatis saat layanan jalan)
)

/**
 * Log disimpan permanen di file (filesDir/activity_log.json), jadi tetap ada setelah
 * aplikasi ditutup atau HP restart.
 *
 * Kapasitas diatur dari layar utama: dibatasi (angka) atau tanpa batas (null).
 * Detail transaksi lengkap hanya disimpan untuk FULL_DETAIL_LIMIT log terbaru; log yang lebih lama
 * diringkas (waktu, sisi, persen, dan harga tetap ada) supaya mode tanpa batas tidak membengkak.
 */
object LogStore {
    private const val FILE_NAME = "activity_log.json"

    /** Kapasitas bawaan. */
    const val DEFAULT_CAPACITY = 300

    /** Jumlah log terbaru yang menyimpan daftar transaksi lengkap. */
    const val FULL_DETAIL_LIMIT = 500

    val entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Kapasitas aktif. null = tanpa batas. */
    @Volatile
    var capacity: Int? = DEFAULT_CAPACITY
        private set

    private var loaded = false
    private var lastId = 0L
    private var saveJob: Job? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        capacity = if (Prefs.logUnlimited(app)) null else Prefs.logMax(app)
        val list = LogTrim.slimOld(LogTrim.capped(read(app), capacity), FULL_DETAIL_LIMIT)
        lastId = list.maxOfOrNull { it.id } ?: 0L
        entries.value = list
    }

    /** Muat di latar belakang (aman untuk dipanggil dari layar utama walau log sudah besar). */
    fun preload(ctx: Context) {
        val app = ctx.applicationContext
        io.launch { ensureLoaded(app) }
    }

    /** ID unik berbasis waktu (aman walau dua log muncul di milidetik yang sama). */
    @Synchronized
    fun newId(): Long {
        val now = System.currentTimeMillis()
        lastId = if (now > lastId) now else lastId + 1
        return lastId
    }

    @Synchronized
    fun add(ctx: Context, e: LogEntry) {
        ensureLoaded(ctx)
        entries.value = LogTrim.prepend(entries.value, e, capacity, FULL_DETAIL_LIMIT)
        scheduleSave(ctx)
    }

    fun addSystem(ctx: Context, title: String, message: String) {
        add(
            ctx,
            LogEntry(
                id = newId(),
                ts = System.currentTimeMillis(),
                type = LogType.SYSTEM,
                title = title,
                message = message,
                seen = true
            )
        )
    }

    /**
     * Ubah kapasitas. cap = null -> tanpa batas. Kalau kapasitas baru lebih kecil dari jumlah log sekarang,
     * log paling lama langsung dihapus.
     */
    @Synchronized
    fun setCapacity(ctx: Context, cap: Int?) {
        ensureLoaded(ctx)
        val app = ctx.applicationContext
        capacity = cap
        Prefs.setLogCapacity(app, unlimited = cap == null, max = cap ?: Prefs.logMax(app))
        entries.value = LogTrim.capped(entries.value, cap)
        scheduleSave(app)
    }

    /** Ukuran file log di penyimpanan (byte), 0 kalau belum ada. */
    fun fileSizeBytes(ctx: Context): Long = file(ctx.applicationContext).length()

    @Synchronized
    fun markSeen(ctx: Context, id: Long) {
        ensureLoaded(ctx)
        if (entries.value.none { it.id == id && !it.seen }) return
        entries.value = entries.value.map { if (it.id == id) it.copy(seen = true) else it }
        scheduleSave(ctx)
    }

    /** Isi harga "setelah sinyal" (label untuk model). Hanya mengisi nilai yang masih kosong. */
    @Synchronized
    fun updateOutcomes(ctx: Context, patches: Map<Long, Outcome>) {
        ensureLoaded(ctx)
        entries.value = entries.value.map { e ->
            val p = patches[e.id]
            if (p == null) e else e.copy(outcome = Outcomes.merge(e.outcome, p))
        }
        scheduleSave(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, ids: Set<Long>) {
        ensureLoaded(ctx)
        entries.value = entries.value.filterNot { it.id in ids }
        scheduleSave(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        entries.value = emptyList()
        scheduleSave(ctx)
    }

    // ------------------------------------------------------------ simpan / baca file

    /** Simpan ditunda supaya beberapa perubahan beruntun cukup satu kali tulis (lebih lama kalau log besar). */
    private fun scheduleSave(ctx: Context) {
        val app = ctx.applicationContext
        saveJob?.cancel()
        saveJob = io.launch {
            delay(if (entries.value.size > 2000) 3000L else 300L)
            write(app, entries.value)
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun read(ctx: Context): List<LogEntry> = try {
        val f = file(ctx)
        if (!f.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(ctx: Context, list: List<LogEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            val text = arr.toString()
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(ctx))) {
                file(ctx).writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            // gagal simpan: log tetap ada di memori, akan dicoba lagi pada perubahan berikutnya
        }
    }

    private fun statsToJson(s: WindowStats): JSONObject = JSONObject()
        .put("n", s.n)
        .put("sec", s.seconds)
        .put("buyCnt", s.buyCnt)
        .put("maxUsdt", s.maxTradeUsdt)
        .put("maxSide", s.maxTradeSide)
        .put("pxOld", s.priceOldest)
        .put("pxHigh", s.priceHigh)
        .put("pxLow", s.priceLow)
        .put("vwap", s.vwap)

    private fun statsFromJson(o: JSONObject): WindowStats = WindowStats(
        n = o.optInt("n"),
        seconds = o.optDouble("sec", 0.0),
        buyCnt = o.optInt("buyCnt"),
        maxTradeUsdt = o.optDouble("maxUsdt", 0.0),
        maxTradeSide = o.optInt("maxSide"),
        priceOldest = o.optDouble("pxOld", 0.0),
        priceHigh = o.optDouble("pxHigh", 0.0),
        priceLow = o.optDouble("pxLow", 0.0),
        vwap = o.optDouble("vwap", 0.0)
    )

    private fun outcomeToJson(o: Outcome): JSONObject = JSONObject()
        .put("p5m", o.px5m)
        .put("p15m", o.px15m)
        .put("p1h", o.px1h)
        .put("p4h", o.px4h)
        .put("p12h", o.px12h)

    private fun outcomeFromJson(o: JSONObject?): Outcome =
        if (o == null) Outcome() else Outcome(
            px5m = o.optDouble("p5m", 0.0),
            px15m = o.optDouble("p15m", 0.0),
            px1h = o.optDouble("p1h", 0.0),
            px4h = o.optDouble("p4h", 0.0),
            px12h = o.optDouble("p12h", 0.0)
        )

    private fun toJson(e: LogEntry): JSONObject {
        val trades = JSONArray()
        for (t in e.trades) {
            trades.put(
                JSONObject()
                    .put("price", t.price)
                    .put("qty", t.qty)
                    .put("time", t.time)
                    .put("status", t.status)
                    .put("ts", t.ts)
            )
        }
        val o = JSONObject()
            .put("id", e.id)
            .put("ts", e.ts)
            .put("type", e.type.name)
            .put("title", e.title)
            .put("message", e.message)
            .put("symbol", e.symbol)
            .put("market", e.market)
            .put("side", e.side)
            .put("buy", e.buy)
            .put("sell", e.sell)
            .put("sharePct", e.sharePct)
            .put("streak", e.streak)
            .put("seen", e.seen)
            .put("trades", trades)
            .put("thrPct", e.thrPct)
            .put("out", outcomeToJson(e.outcome))
        e.stats?.let { o.put("stats", statsToJson(it)) }
        return o
    }

    private fun fromJson(o: JSONObject): LogEntry {
        val tr = o.optJSONArray("trades")
        val trades = if (tr == null) {
            emptyList()
        } else {
            (0 until tr.length()).map {
                val t = tr.getJSONObject(it)
                Trade(
                    price = t.getDouble("price"),
                    qty = t.getDouble("qty"),
                    time = t.getString("time"),
                    status = t.getString("status"),
                    ts = t.optLong("ts")
                )
            }
        }
        return LogEntry(
            id = o.getLong("id"),
            ts = o.getLong("ts"),
            type = try {
                LogType.valueOf(o.getString("type"))
            } catch (e: Exception) {
                LogType.SYSTEM
            },
            title = o.getString("title"),
            message = o.getString("message"),
            symbol = o.optString("symbol"),
            market = o.optString("market"),
            side = o.optString("side"),
            buy = o.optDouble("buy", 0.0),
            sell = o.optDouble("sell", 0.0),
            sharePct = o.optInt("sharePct"),
            streak = o.optInt("streak"),
            trades = trades,
            seen = o.optBoolean("seen", true),
            thrPct = o.optDouble("thrPct", 0.0),
            stats = o.optJSONObject("stats")?.let { statsFromJson(it) },
            outcome = outcomeFromJson(o.optJSONObject("out"))
        )
    }
}