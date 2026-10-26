package com.example.futuressignal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.futuressignal.data.FeedRules
import com.example.futuressignal.data.Fmt
import com.example.futuressignal.data.LogCsv
import com.example.futuressignal.data.LogEntry
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.LogType
import com.example.futuressignal.data.MinuteStore
import com.example.futuressignal.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val GREEN = Color(0xFF0ECB81)
private val RED = Color(0xFFF6465D)

private fun dateTime(ms: Long): String {
    val f = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale("id", "ID"))
    f.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    return f.format(Date(ms))
}

private fun sideColor(side: String): Color = when (side) {
    "BUY" -> GREEN
    "SELL" -> RED
    else -> Color.Unspecified
}

private enum class DeleteMode { SELECTED, ALL }

// =====================================================================================
// DAFTAR LOG  (centang beberapa lalu "Hapus", atau "Hapus semua")
// =====================================================================================
@Composable
fun LogListScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current

    // Unduh: pengguna memilih tempat simpan (Downloads dll.), lalu .zip berisi satu CSV per koin per pasar ditulis ke sana
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val hasMinuteData = remember { MinuteStore.totalBytes(ctx) > 0L }
    val exportScope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            exportMsg = "Menyimpan…"
            exportScope.launch {
                val summary = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            LogCsv.writeZip(
                                out,
                                LogStore.entries.value,
                                FeedRules.DEFAULT_SYMBOLS + Prefs.watchSymbols(ctx),
                                MinuteStore.files(ctx)
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                exportMsg = if (summary == null) {
                    "Gagal menyimpan file."
                } else {
                    "Tersimpan: ${summary.rows} baris sinyal di ${summary.files} file CSV" +
                            (if (summary.minuteFiles > 0) " + ${summary.minuteFiles} file data per menit" else "") +
                            " (dalam .zip)."
                }
            }
        }
    }
    val entries by LogStore.entries.collectAsState()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf<DeleteMode?>(null) }

    // abaikan pilihan yang log-nya sudah tidak ada
    val idSet = remember(entries) { entries.mapTo(HashSet<Long>(entries.size)) { it.id } }
    val selectedNow = remember(selected, idSet) { selected.filterTo(HashSet<Long>()) { it in idSet } }
    val allSelected = entries.isNotEmpty() && selectedNow.size == entries.size

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Kembali") }
                Text(
                    "Log Aktivitas (${entries.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selected = if (allSelected) emptySet() else entries.map { it.id }.toSet()
                    },
                    enabled = entries.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (allSelected) "Batal pilih" else "Pilih semua")
                }
                Button(
                    onClick = { confirm = DeleteMode.SELECTED },
                    enabled = selectedNow.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Hapus (${selectedNow.size})")
                }
                OutlinedButton(
                    onClick = { confirm = DeleteMode.ALL },
                    enabled = entries.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Hapus semua")
                }
            }

            OutlinedButton(
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("Asia/Jakarta") }
                        .format(Date())
                    saveLauncher.launch("futures_signal_log_$stamp.zip")
                },
                enabled = entries.any { it.type == LogType.SIGNAL } || hasMinuteData,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Unduh data (CSV dalam .zip)") }
            exportMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text("Belum ada log.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(entries, key = { it.id }) { e ->
                        LogRow(
                            e = e,
                            checked = e.id in selectedNow,
                            onCheck = { on -> selected = if (on) selectedNow + e.id else selectedNow - e.id },
                            onOpen = { onOpen(e.id) }
                        )
                    }
                }
            }
        }
    }

    confirm?.let { mode ->
        val count = if (mode == DeleteMode.ALL) entries.size else selectedNow.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Hapus log?") },
            text = { Text("$count log akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    if (mode == DeleteMode.ALL) LogStore.clear(ctx) else LogStore.delete(ctx, selectedNow)
                    selected = emptySet()
                    confirm = null
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun LogRow(e: LogEntry, checked: Boolean, onCheck: (Boolean) -> Unit, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 8.dp, end = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.type == LogType.SIGNAL && !e.seen) {
                        // titik = sinyal yang belum pernah dibuka (kemungkinan terlewat)
                        Text("● ", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(e.title, fontWeight = FontWeight.Bold, color = sideColor(e.side))
                }
                Text(dateTime(e.ts), style = MaterialTheme.typography.bodySmall)
                Text(
                    e.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// =====================================================================================
// DETAIL LOG
// =====================================================================================
@Composable
fun LogDetailScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries by LogStore.entries.collectAsState()
    val e = entries.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }

    // Membuka detail = menandai sudah dibaca
    LaunchedEffect(id) { LogStore.markSeen(ctx, id) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { TextButton(onClick = onBack) { Text("‹ Kembali") } }

            if (e == null) {
                item { Text("Log tidak ditemukan (mungkin sudah dihapus).") }
            } else {
                item {
                    Text(
                        e.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = sideColor(e.side)
                    )
                }
                item { Text("${dateTime(e.ts)} WIB", style = MaterialTheme.typography.bodyMedium) }
                item { Text(e.message, style = MaterialTheme.typography.bodyMedium) }

                if (e.type == LogType.SIGNAL && e.symbol.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                InfoRow("Pasangan", e.symbol)
                                InfoRow("Pasar", e.market)
                                InfoRow("Sisi dominan", e.side)
                                InfoRow("Dominasi", "${e.sharePct}%")
                                InfoRow("Total Buy", "${Fmt.compact(e.buy)} USDT")
                                InfoRow("Total Sell", "${Fmt.compact(e.sell)} USDT")
                                InfoRow("Berturut-turut", "${e.streak}x")
                            }
                        }
                    }
                }

                if (e.trades.isNotEmpty()) {
                    item {
                        Text(
                            "Transaksi saat sinyal muncul (${e.trades.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("Harga", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text(
                                "Jumlah (USDT)",
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Waktu",
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    items(e.trades) { t ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                Fmt.price(t.price),
                                color = if (t.isBuy) GREEN else RED,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                Fmt.compact(t.qty),
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                t.time,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { confirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Hapus log ini") }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus log ini?") },
            text = { Text("Log akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    LogStore.delete(ctx, setOf(id))
                    onBack()
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}