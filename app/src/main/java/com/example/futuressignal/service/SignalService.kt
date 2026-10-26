package com.example.futuressignal.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import com.example.futuressignal.MainActivity
import com.example.futuressignal.R
import com.example.futuressignal.data.FeedStatus
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.Market
import com.example.futuressignal.data.MinuteCsv
import com.example.futuressignal.data.MinuteRecorder
import com.example.futuressignal.data.MinuteStore
import com.example.futuressignal.data.Outcomes
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.ServiceState
import com.example.futuressignal.data.Snapshot
import com.example.futuressignal.data.SnapshotParser
import com.example.futuressignal.data.SnapshotStore
import com.example.futuressignal.data.Source
import com.example.futuressignal.signal.SignalConfig
import com.example.futuressignal.signal.SignalEngine
import com.example.futuressignal.signal.SignalNotifier
import com.example.futuressignal.widget.FutureBtcWidget
import com.example.futuressignal.widget.FutureSolWidget
import com.example.futuressignal.widget.TradeBtcWidget
import com.example.futuressignal.widget.TradeSolWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Foreground service: menjaga koneksi WebSocket tetap hidup di latar belakang,
 * menjalankan SignalEngine, dan mendorong data terbaru ke 4 widget.
 */
class SignalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val engine = SignalEngine()
    private val widgets: List<GlanceAppWidget> = listOf(
        FutureBtcWidget(), FutureSolWidget(), TradeBtcWidget(), TradeSolWidget()
    )

    @Volatile private var socket: WebSocket? = null
    private var feed: BinanceFeed? = null
    private var lastFeedError: String? = null

    // Perekam data per menit (bahan model): bekerja untuk kedua sumber data
    @Volatile private var recorder = MinuteRecorder()
    @Volatile private var recordMinutes = true
    private var recordCheckedAt = 0L
    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var dirty = false
    private var failLogged = false
    private var attempt = 0
    private var statusText = "Menghubungkan…"
    private var reconnectJob: Job? = null
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            ServiceState.enabled.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TOGGLE_SOUND) {
            // diketuk dari notifikasi layanan: bisukan / nyalakan suara tanpa membuka aplikasi
            SignalNotifier.setSoundEnabled(this, !Prefs.soundEnabled(this))
            return if (running) {
                refreshOngoing()
                START_STICKY
            } else {
                stopSelf()
                START_NOT_STICKY
            }
        }

        if (intent?.action == ACTION_RELOAD) {
            // Sumber data atau pengaturan koin diubah dari layar utama: sambungkan ulang dengan pengaturan baru
            return if (running) {
                reloadSource()
                START_STICKY
            } else {
                stopSelf()
                START_NOT_STICKY
            }
        }

        SignalNotifier.createChannels(this)
        SignalNotifier.prepare(this)
        SignalConfig.load(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildOngoing("Menghubungkan…"), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            LogStore.addSystem(this, "Layanan dinyalakan", sourceNote())
            acquireWakeLock()
            startTicker()
            startSource()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- WebSocket

    private fun connect() {
        if (!running) return
        val request = try {
            Request.Builder().url(Prefs.wsUrl(this).trim()).build()
        } catch (e: IllegalArgumentException) {
            updateOngoing("URL WebSocket tidak valid")
            return
        }

        val gen = ++generation
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                attempt = 0
                setConnected(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected(gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected(gen)
            }
        })
    }

    private fun onDisconnected(gen: Int) {
        if (gen != generation || !running) return
        setConnected(false)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running) return
        reconnectJob?.cancel()
        val delayMs = min(1000L shl min(attempt, 5), 30_000L)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun handleMessage(text: String) {
        val snap = SnapshotParser.parse(text) ?: return
        handleSnapshot(text, snap)
    }

    /** Pintu masuk data dari kedua sumber: server sendiri (raw = JSON asli) dan Binance langsung (raw = null). */
    private fun handleSnapshot(raw: String?, snap: Snapshot) {
        SnapshotStore.update(raw, snap)
        dirty = true
        engine.onSnapshot(snap).forEach { SignalNotifier.fire(this, it) }
        if (shouldRecordMinutes()) {
            recorder.onSnapshot(snap)
            writeMinuteRows()
        }
    }

    /** Status rekam dibaca ulang paling sering sekali per detik (bisa diubah dari layar utama tanpa restart). */
    private fun shouldRecordMinutes(): Boolean {
        val now = System.currentTimeMillis()
        if (now - recordCheckedAt > 1000L) {
            recordCheckedAt = now
            recordMinutes = Prefs.recordMinutes(this)
        }
        return recordMinutes
    }

    /** Tulis baris per menit yang sudah siap ke file CSV (satu file per koin per pasar). */
    private fun writeMinuteRows() {
        val rows = recorder.drain()
        if (rows.isEmpty()) return
        val header = MinuteCsv.header(recorder.thresholds)
        for ((key, row) in rows) {
            MinuteStore.append(applicationContext, key.first, key.second, header, MinuteCsv.line(row))
        }
    }

    /** Keluarkan semua menit yang sudah selesai (saat layanan berhenti atau sumber diganti). */
    private fun flushMinuteData() {
        recorder.flush()
        writeMinuteRows()
    }

    // ---------------------------------------------------------------- sumber data (server sendiri / Binance langsung)

    private fun startSource() {
        if (Prefs.source(this) == Source.BINANCE) {
            startBinance()
        } else {
            ServiceState.feedInfo.value = ""
            connect()
        }
    }

    private fun startBinance() {
        val cfg = Prefs.binanceConfig(this)
        ServiceState.feedInfo.value = "Binance langsung: menghubungkan ${cfg.connectionCount} koneksi…"
        feed = BinanceFeed(
            client = client,
            scope = scope,
            config = cfg,
            onSnapshot = { snap -> handleSnapshot(null, snap) },
            onStatus = { st -> onFeedStatus(st) },
            onBar = { sym, mkt, bar -> if (shouldRecordMinutes()) recorder.onRawBar(sym, mkt, bar) }
        ).also { it.start() }
    }

    @Synchronized
    private fun onFeedStatus(st: FeedStatus) {
        ServiceState.feedInfo.value = st.describe()
        val any = st.connected > 0
        val was = ServiceState.connected.value
        when {
            any && !was -> setConnected(true)
            !any && was -> setConnected(false)
            !any && !was && !failLogged && st.errors.isNotEmpty() -> setConnected(false)   // dicatat "Gagal terhubung" sekali
        }
        // alasan masalah dicatat ke log (sekali per pesan yang berbeda), mis. "diblokir dari jaringan ini (HTTP 451)"
        val firstError = st.errors.firstOrNull()
        if (firstError != null && firstError != lastFeedError) {
            lastFeedError = firstError
            LogStore.addSystem(this, "Masalah sumber data", firstError)
        } else if (firstError == null) {
            lastFeedError = null
        }
        updateOngoing("${if (any) "Terhubung" else "Terputus"} • ${st.describe()}")
    }

    /** Hentikan sumber yang sedang jalan (WebSocket server sendiri maupun Binance langsung). */
    private fun stopSource() {
        generation++                       // abaikan semua callback WebSocket server sendiri yang lama
        reconnectJob?.cancel()
        socket?.cancel()
        socket = null
        feed?.stop()
        feed = null
    }

    private fun reloadSource() {
        stopSource()
        flushMinuteData()
        recorder = MinuteRecorder()        // mulai bersih: rangkaian dominasi tidak boleh terbawa dari sumber lama
        attempt = 0
        failLogged = false
        lastFeedError = null
        ServiceState.connected.value = false
        Prefs.setLive(this, false)
        SnapshotStore.clear(applicationContext)      // jangan campur data sumber lama dengan yang baru
        ServiceState.lastData.value = 0L
        dirty = true
        updateOngoing("Menyambung ulang…")
        LogStore.addSystem(this, "Sumber data diganti", sourceNote())
        startSource()
    }

    private fun sourceNote(): String =
        if (Prefs.source(this) == Source.BINANCE) {
            val c = Prefs.binanceConfig(this)
            val markets = listOfNotNull(if (c.spot) "Trade" else null, if (c.future) "Future" else null).joinToString(" + ")
            "Sumber: Binance langsung • ${c.symbols.joinToString(", ")} • $markets • limit ${c.limit}"
        } else {
            "Sumber: server sendiri"
        }

    /** Catat harga "setelah sinyal" (5 mnt, 15 mnt, 1/4/12 jam) sebagai label untuk model; hanya bila data masih segar. */
    private fun trackOutcomes() {
        val now = System.currentTimeMillis()
        if (now - SnapshotStore.updatedAt > 15_000L) return
        val snap = SnapshotStore.snapshot ?: return
        val patches = Outcomes.findDue(LogStore.entries.value, now) { symbol, marketLabel ->
            val m = Market.values().firstOrNull { it.label == marketLabel }
            if (m == null) null else snap[symbol]?.get(m)?.firstOrNull()?.price
        }
        if (patches.isNotEmpty()) LogStore.updateOutcomes(applicationContext, patches)
    }

    // ---------------------------------------------------------------- Widget

    /** Tiap 1,5 detik: kalau ada data baru, simpan dan segarkan 4 widget. */
    private fun startTicker() {
        tickerJob = scope.launch {
            while (isActive) {
                delay(1500)
                if (dirty) {
                    dirty = false
                    SnapshotStore.flush(applicationContext)
                    ServiceState.lastData.value = SnapshotStore.updatedAt
                    refreshWidgets()
                }
                trackOutcomes()
            }
        }
    }

    private suspend fun refreshWidgets() {
        for (w in widgets) {
            try {
                w.updateAll(applicationContext)
            } catch (e: Exception) {
                // abaikan, akan dicoba lagi pada tick berikutnya
            }
        }
    }

    // ---------------------------------------------------------------- Notifikasi & status

    private fun setConnected(v: Boolean) {
        val was = ServiceState.connected.value
        ServiceState.connected.value = v
        Prefs.setLive(this, v)
        dirty = true
        updateOngoing(if (v) "Terhubung • menerima data realtime" else "Terputus • mencoba menyambung ulang…")

        // Catat perubahan koneksi ke log (hanya saat berubah, bukan tiap percobaan ulang)
        if (v) {
            failLogged = false
            LogStore.addSystem(this, "Terhubung", "WebSocket tersambung, data realtime diterima")
        } else if (was) {
            failLogged = true
            LogStore.addSystem(
                this, "Terputus",
                "Koneksi terputus. Sinyal tidak terdeteksi sampai tersambung lagi (mencoba ulang otomatis)"
            )
        } else if (!failLogged) {
            failLogged = true
            LogStore.addSystem(
                this, "Gagal terhubung",
                "Tidak bisa menyambung ke sumber data. Periksa pengaturan sumber data dan koneksi internet (mencoba ulang otomatis)"
            )
        }
    }

    private fun updateOngoing(text: String) {
        statusText = text
        refreshOngoing()
    }

    /** Pasang ulang notifikasi layanan dengan teks status terakhir (mis. setelah suara dibisukan). */
    private fun refreshOngoing() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildOngoing(statusText))
    }

    private fun buildOngoing(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SignalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, SignalService::class.java).setAction(ACTION_TOGGLE_SOUND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val soundOn = Prefs.soundEnabled(this)
        return NotificationCompat.Builder(this, SignalNotifier.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Futures Signal aktif")
            .setContentText(if (soundOn) text else "$text • suara mati")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (soundOn) "Bisukan suara" else "Nyalakan suara", toggle)
            .addAction(0, "Matikan", stop)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FuturesSignal:ws").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        val wasRunning = running
        running = false
        stopSource()
        flushMinuteData()
        tickerJob?.cancel()
        client.dispatcher.executorService.shutdown()

        ServiceState.connected.value = false
        Prefs.setLive(this, false)
        SnapshotStore.flush(applicationContext)
        if (wasRunning) {
            LogStore.addSystem(this, "Layanan dimatikan", "Sinyal tidak dipantau sampai layanan dinyalakan lagi")
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }

        // satu kali segarkan widget supaya menampilkan status OFF
        val app = applicationContext
        val ws = widgets
        CoroutineScope(Dispatchers.Default).launch {
            for (w in ws) {
                try {
                    w.updateAll(app)
                } catch (e: Exception) {
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.futuressignal.STOP"
        const val ACTION_TOGGLE_SOUND = "com.example.futuressignal.TOGGLE_SOUND"
        const val ACTION_RELOAD = "com.example.futuressignal.RELOAD"
        private const val NOTIF_ID = 1001

        /** Satu-satunya pintu ON/OFF: simpan status, lalu start/stop service. */
        fun setEnabled(ctx: Context, on: Boolean) {
            Prefs.setEnabled(ctx, on)
            ServiceState.enabled.value = on
            val intent = Intent(ctx, SignalService::class.java)
            if (on) ContextCompat.startForegroundService(ctx, intent) else ctx.stopService(intent)
        }

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SignalService::class.java))
        }

        /** Terapkan pengaturan sumber data yang baru. Tidak berbuat apa-apa bila layanan sedang mati. */
        fun reload(ctx: Context) {
            if (Prefs.isEnabled(ctx)) {
                ctx.startService(Intent(ctx, SignalService::class.java).setAction(ACTION_RELOAD))
            }
        }
    }
}