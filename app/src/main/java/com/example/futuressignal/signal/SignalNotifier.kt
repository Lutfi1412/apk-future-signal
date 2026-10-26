package com.example.futuressignal.signal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.futuressignal.MainActivity
import com.example.futuressignal.R
import com.example.futuressignal.data.LogEntry
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.LogType
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.ServiceState

object SignalNotifier {
    const val CHANNEL_SIGNAL = "signal_alerts"
    const val CHANNEL_SERVICE = "signal_service"

    @Volatile
    private var sound: SoundPlayer? = null

    /** Siapkan mesin suara lebih awal supaya sinyal pertama sudah bisa dibacakan. */
    @Synchronized
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        if (Prefs.soundEnabled(app) && sound == null) sound = SoundPlayer(app)
    }

    /**
     * Satu-satunya pintu ON/OFF suara: simpan, perbarui status untuk layar, lalu hentikan suara yang
     * sedang berjalan bila dimatikan (atau siapkan mesin suara bila dinyalakan).
     */
    @Synchronized
    fun setSoundEnabled(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        Prefs.setSoundEnabled(app, on)
        ServiceState.soundEnabled.value = on
        if (on) {
            if (sound == null) sound = SoundPlayer(app)
        } else {
            sound?.stopAll()
        }
    }

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alerts = NotificationChannel(
            CHANNEL_SIGNAL, "Sinyal Trading", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)          // suara diputar sendiri oleh SoundPlayer
            enableVibration(true)
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE, "Status Layanan", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(alerts)
        nm.createNotificationChannel(service)
    }

    /** Catat ke log, tampilkan notifikasi, lalu bunyi notif.mp3 + suara keterangan. */
    @Synchronized
    fun fire(ctx: Context, s: Signal) {
        val app = ctx.applicationContext
        createChannels(app)
        val soundOn = Prefs.soundEnabled(app)
        if (soundOn && sound == null) sound = SoundPlayer(app)

        // 1) Catat ke log aktivitas dulu (permanen), supaya tidak hilang walau notifikasi gagal
        val entry = LogEntry(
            id = LogStore.newId(),
            ts = System.currentTimeMillis(),
            type = LogType.SIGNAL,
            title = s.title,
            message = s.message,
            symbol = s.symbol,
            market = s.market,
            side = s.side,
            buy = s.buy,
            sell = s.sell,
            sharePct = s.sharePct,
            streak = s.streak,
            trades = s.trades,
            thrPct = s.thresholdPct,
            stats = s.stats
        )
        LogStore.add(app, entry)

        // 2) Notifikasi: diketuk akan membuka detail log sinyal ini
        val canNotify = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        if (canNotify) {
            val open = PendingIntent.getActivity(
                app,
                (entry.id % Int.MAX_VALUE).toInt(),   // requestCode unik -> extra tidak saling menimpa
                Intent(app, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LOG_ID, entry.id)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CHANNEL_SIGNAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(s.title)
                .setContentText(s.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(s.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(app).notify(s.id.hashCode(), n)
        }

        // 3) Suara (dilewati kalau suara dimatikan; notifikasi dan log tetap berjalan)
        if (soundOn) sound?.play(s.speech)
    }
}