package com.example.futuressignal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.futuressignal.data.Fmt
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.LogType
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.ServiceState
import com.example.futuressignal.service.SignalService
import com.example.futuressignal.signal.SignalConfig
import com.example.futuressignal.signal.Signal
import com.example.futuressignal.signal.SignalNotifier

@Composable
fun MainScreen(onOpenLog: () -> Unit, onOpenAnalysis: () -> Unit) {
    val ctx = LocalContext.current
    val running by ServiceState.enabled.collectAsState()
    val connected by ServiceState.connected.collectAsState()
    val lastData by ServiceState.lastData.collectAsState()
    val soundOn by ServiceState.soundEnabled.collectAsState()
    val logs by LogStore.entries.collectAsState()
    val unseen = logs.count { it.type == LogType.SIGNAL && !it.seen }
    var url by remember { mutableStateOf(Prefs.wsUrl(ctx)) }

    // Pengaturan sinyal (2 input + Simpan)
    var streakText by remember { mutableStateOf(Prefs.streak(ctx).toString()) }
    var pctText by remember { mutableStateOf(pctToText(Prefs.dominancePct(ctx))) }
    var streakError by remember { mutableStateOf<String?>(null) }
    var pctError by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    // Izin notifikasi (Android 13+). Layanan tetap dinyalakan walau izin ditolak (suara tetap bunyi).
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SignalService.setEnabled(ctx, true)
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Futures Signal", style = MaterialTheme.typography.headlineMedium)
            }

            // Tombol ON/OFF
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (running) "Layanan: ON" else "Layanan: OFF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    !running -> "Tidak berjalan"
                                    connected -> "Terhubung • realtime"
                                    else -> "Menghubungkan…"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (lastData > 0) {
                                Text(
                                    "Data terakhir: ${Fmt.time(lastData)} WIB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Switch(
                            checked = running,
                            onCheckedChange = { on ->
                                if (on) {
                                    Prefs.setWsUrl(ctx, url.trim())
                                    val needPerm = Build.VERSION.SDK_INT >= 33 &&
                                            ContextCompat.checkSelfPermission(
                                                ctx, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                    if (needPerm) {
                                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        SignalService.setEnabled(ctx, true)
                                    }
                                } else {
                                    SignalService.setEnabled(ctx, false)
                                }
                            }
                        )
                    }
                }
            }

            // Suara notifikasi (bunyi + suara bicara) ON/OFF
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (soundOn) "Suara notifikasi: ON" else "Suara notifikasi: OFF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (soundOn) "Setiap sinyal disertai bunyi dan suara bicara."
                                else "Senyap: notifikasi tetap muncul dan dicatat di log, tanpa bunyi dan suara bicara.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = soundOn,
                            onCheckedChange = { SignalNotifier.setSoundEnabled(ctx, it) }
                        )
                    }
                }
            }

            // Log aktivitas (penanda jumlah sinyal yang belum dibuka)
            item {
                Button(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (unseen > 0) "Log aktivitas • $unseen sinyal baru"
                        else "Log aktivitas (${logs.size})"
                    )
                }
            }

            // Analisis (grafik + kesimpulan dari log)
            item {
                OutlinedButton(onClick = onOpenAnalysis, modifier = Modifier.fillMaxWidth()) {
                    Text("Analisis (grafik & kesimpulan)")
                }
            }

            // Pengaturan sinyal: berturut-turut & minimum unggul (%)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Pengaturan sinyal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Sinyal bunyi bila porsi BUY atau SELL minimal sekian persen dari total, " +
                                    "sekian kali berturut-turut saat data berubah. Berlaku langsung tanpa restart layanan.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = streakText,
                                onValueChange = {
                                    streakText = it.filter { c -> c.isDigit() }.take(3)
                                    savedMsg = null
                                },
                                label = { Text("Berturut-turut") },
                                suffix = { Text("kali") },
                                singleLine = true,
                                isError = streakError != null,
                                supportingText = { streakError?.let { Text(it) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = pctText,
                                onValueChange = {
                                    pctText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5)
                                    savedMsg = null
                                },
                                label = { Text("Minimum unggul") },
                                suffix = { Text("%") },
                                singleLine = true,
                                isError = pctError != null,
                                supportingText = { pctError?.let { Text(it) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                val s = streakText.toIntOrNull()
                                val p = pctText.replace(',', '.').toDoubleOrNull()
                                streakError = if (s == null || s !in 1..200) "Isi 1–200" else null
                                pctError = if (p == null || p < 51.0 || p > 100.0) "Isi 51–100" else null
                                if (s != null && p != null && streakError == null && pctError == null) {
                                    SignalConfig.save(ctx, s, p)
                                    savedMsg = "Tersimpan: minimum ${pctToText(p)}% • $s kali berturut-turut. " +
                                            "Hitungan berturut-turut yang sedang berjalan dimulai dari awal."
                                } else {
                                    savedMsg = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Simpan") }
                        savedMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "Bawaan: ${Prefs.DEFAULT_DOMINANCE_PCT.toInt()}% • ${Prefs.DEFAULT_STREAK} kali",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Kapasitas log untuk analisis (dibatasi / tanpa batas)
            item { LogCapacityCard() }

            // Sumber data: Binance langsung / server sendiri
            item { DataSourceCard() }

            // Data per menit untuk melatih model
            item { MinuteDataCard() }

            // Alamat WebSocket
            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Alamat WebSocket server sendiri (wss://…/ws)") },
                    singleLine = true,
                    readOnly = running,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Optimasi baterai
            item {
                val pm = ctx.getSystemService(PowerManager::class.java)
                val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ignoring) "Baterai: sudah tidak dibatasi" else "Izinkan jalan di latar belakang (baterai)")
                }
            }

            item {
                Text(
                    "Widget: tahan layar home → Widget → Futures Signal → pilih Future 1 / Future 2 / Trade 1 / Trade 2 (koin pertama dan kedua; bawaan BTC dan SOL).",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // ===== TESTING START (hapus blok ini kalau sudah selesai testing) =====
            item {
                Button(
                    onClick = {
                        SignalNotifier.fire(
                            ctx,
                            Signal(
                                id = "TEST_BUTTON",
                                title = "TES • Manual",
                                message = "Notifikasi uji coba dari tombol (hapus blok TESTING di MainScreen.kt)",
                                speech = "Tes notifikasi manual berhasil."
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tes notifikasi + suara")
                }
            }
            // ===== TESTING END =====
        }
    }
}

/** 88.0 -> "88", 87.5 -> "87.5" */
private fun pctToText(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()