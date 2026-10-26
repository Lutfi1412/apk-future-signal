package com.example.futuressignal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.futuressignal.data.MinuteRecorder
import com.example.futuressignal.data.MinuteStore
import com.example.futuressignal.data.Prefs
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Perekaman data per menit untuk melatih model: harga (open/high/low/close), aliran, dominasi BUY di jendela transaksi,
 * dan panjang rangkaian dominasi untuk beberapa ambang. Berjalan di latar belakang bersama layanan, di kedua sumber data.
 */
@Composable
fun MinuteDataCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.recordMinutes(ctx)) }
    var bytes by remember { mutableStateOf(MinuteStore.totalBytes(ctx)) }
    var fileCount by remember { mutableStateOf(MinuteStore.files(ctx).size) }
    var confirm by remember { mutableStateOf(false) }

    // segarkan ukuran data tiap 5 detik selama kartu terlihat
    LaunchedEffect(Unit) {
        while (true) {
            bytes = MinuteStore.totalBytes(ctx)
            fileCount = MinuteStore.files(ctx).size
            delay(5_000L)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (on) "Data per menit: ON" else "Data per menit: OFF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Merekam bahan untuk melatih model, satu baris per menit per koin per pasar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = on, onCheckedChange = {
                    on = it
                    Prefs.setRecordMinutes(ctx, it)
                })
            }
            Text(
                "Isi tiap baris: harga open/high/low/close, aliran (volume, taker-buy), dominasi BUY di jendela transaksi, " +
                        "dan panjang rangkaian dominasi untuk ambang ${MinuteRecorder.DEFAULT_THRESHOLDS.joinToString("/")}% " +
                        "(jumlah pembaruan dan detik). Ambang sinyal ditentukan nanti di notebook, jadi bisa dicoba ulang tanpa merekam lagi.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Terbaik memakai Binance langsung (harga dan volume dari transaksi mentah) dengan limit minimal 100. " +
                        "Dengan server sendiri, kolom volume kosong dan harga dari cuplikan transaksi.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Tersimpan: $fileCount file • ${bytesLabel(bytes)}. Ikut diunduh dari layar Log aktivitas.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { confirm = true },
                enabled = bytes > 0L,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Hapus data per menit") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus data per menit?") },
            text = { Text("Semua file data per menit (${bytesLabel(bytes)}) akan dihapus permanen. Unduh dulu kalau masih dibutuhkan.") },
            confirmButton = {
                TextButton(onClick = {
                    MinuteStore.clear(ctx)
                    bytes = 0L
                    fileCount = 0
                    confirm = false
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Batal") }
            }
        )
    }
}

private fun bytesLabel(b: Long): String = when {
    b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}