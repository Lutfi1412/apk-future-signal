package com.example.futuressignal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.Prefs
import java.util.Locale

/** Pengaturan kapasitas log yang dipakai halaman Analisis: dibatasi (isi angka) atau tanpa batas. */
@Composable
fun LogCapacityCard() {
    val ctx = LocalContext.current
    val entries by LogStore.entries.collectAsState()
    val count = entries.size

    var unlimited by remember { mutableStateOf(Prefs.logUnlimited(ctx)) }
    var maxText by remember { mutableStateOf(Prefs.logMax(ctx).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }
    var confirmCap by remember { mutableStateOf<Int?>(null) }

    // kapasitas yang sedang aktif (bukan yang sedang diketik)
    var activeUnlimited by remember { mutableStateOf(Prefs.logUnlimited(ctx)) }
    var activeMax by remember { mutableStateOf(Prefs.logMax(ctx)) }
    val sizeText = remember(count) { formatBytes(LogStore.fileSizeBytes(ctx)) }

    val applyCap: (Int?) -> Unit = { cap ->
        LogStore.setCapacity(ctx, cap)
        activeUnlimited = cap == null
        if (cap != null) activeMax = cap
        savedMsg = if (cap == null) "Tersimpan: tanpa batas." else "Tersimpan: maksimum $cap entri."
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Kapasitas log (untuk analisis)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Log yang tersimpan dipakai halaman Analisis. Batasi jumlahnya, atau lepaskan tanpa batas.",
                style = MaterialTheme.typography.bodySmall
            )

            // Pilihan 1: dibatasi
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    unlimited = false
                    savedMsg = null
                }
            ) {
                RadioButton(selected = !unlimited, onClick = {
                    unlimited = false
                    savedMsg = null
                })
                Text("Dibatasi", style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedTextField(
                value = maxText,
                onValueChange = {
                    maxText = it.filter { c -> c.isDigit() }.take(7)
                    error = null
                    savedMsg = null
                },
                enabled = !unlimited,
                label = { Text("Jumlah maksimum log") },
                suffix = { Text("entri") },
                singleLine = true,
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // Pilihan 2: tanpa batas
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    unlimited = true
                    error = null
                    savedMsg = null
                }
            ) {
                RadioButton(selected = unlimited, onClick = {
                    unlimited = true
                    error = null
                    savedMsg = null
                })
                Text("Tanpa batas", style = MaterialTheme.typography.bodyLarge)
            }
            if (unlimited) {
                Text(
                    "Log terus bertambah selama layanan aktif dan memakai penyimpanan serta memori HP. " +
                            "Pantau ukurannya di bawah dan hapus log dari halaman Log aktivitas bila perlu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                "Daftar transaksi lengkap hanya disimpan untuk ${LogStore.FULL_DETAIL_LIMIT} log terbaru. " +
                        "Log yang lebih lama diringkas (waktu, sisi, persen, dan harga tetap ada), jadi tetap dipakai analisis.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = {
                    savedMsg = null
                    if (unlimited) {
                        applyCap(null)
                    } else {
                        val n = maxText.toIntOrNull()
                        if (n == null || n !in 10..1_000_000) {
                            error = "Isi 10–1.000.000"
                        } else {
                            error = null
                            // kapasitas lebih kecil dari jumlah log sekarang -> konfirmasi dulu
                            if (n < count) confirmCap = n else applyCap(n)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simpan") }

            savedMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Saat ini: ${if (activeUnlimited) "tanpa batas" else "maksimum $activeMax entri"} • " +
                        "tersimpan $count entri (±$sizeText)",
                style = MaterialTheme.typography.bodySmall
            )
            if (count > 5000) {
                Text(
                    "Log sudah besar ($count entri). Aplikasi bisa terasa lebih lambat; pertimbangkan membatasi atau menghapus log lama.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    confirmCap?.let { cap ->
        AlertDialog(
            onDismissRequest = { confirmCap = null },
            title = { Text("Kurangi kapasitas log?") },
            text = { Text("${count - cap} log paling lama akan dihapus permanen agar sesuai kapasitas $cap.") },
            confirmButton = {
                TextButton(onClick = {
                    applyCap(cap)
                    confirmCap = null
                }) { Text("Hapus & simpan") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCap = null }) { Text("Batal") }
            }
        )
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}