package com.example.futuressignal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.example.futuressignal.data.BinanceConfig
import com.example.futuressignal.data.FeedRules
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.ServiceState
import com.example.futuressignal.data.Source
import com.example.futuressignal.service.SignalService

/**
 * Pilihan sumber data: Binance langsung (koin, pasar, dan limit bisa diatur, seperti API pribadi)
 * atau server sendiri (alamat WebSocket di kolom di bawahnya). Berlaku langsung tanpa mematikan layanan.
 */
@Composable
fun DataSourceCard() {
    val ctx = LocalContext.current
    val feedInfo by ServiceState.feedInfo.collectAsState()
    val serviceOn by ServiceState.enabled.collectAsState()

    val cfg0 = remember { Prefs.binanceConfig(ctx) }
    var source by remember { mutableStateOf(Prefs.source(ctx)) }
    var activeSource by remember { mutableStateOf(Prefs.source(ctx)) }
    var symbolsText by remember { mutableStateOf(cfg0.symbols.joinToString(",")) }
    var limitText by remember { mutableStateOf(cfg0.limit.toString()) }
    var spot by remember { mutableStateOf(cfg0.spot) }
    var future by remember { mutableStateOf(cfg0.future) }
    var symbolsError by remember { mutableStateOf<String?>(null) }
    var limitError by remember { mutableStateOf<String?>(null) }
    var marketError by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    val previewCount = FeedRules.parseSymbols(symbolsText).symbols.size *
            ((if (spot) 1 else 0) + (if (future) 1 else 0))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sumber data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // ---- pilihan 1: Binance langsung
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    source = Source.BINANCE
                    savedMsg = null
                }
            ) {
                RadioButton(selected = source == Source.BINANCE, onClick = {
                    source = Source.BINANCE
                    savedMsg = null
                })
                Column {
                    Text("Binance langsung (publik)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Aplikasi terhubung ke WebSocket publik Binance, satu koneksi per koin per pasar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (source == Source.BINANCE) {
                OutlinedTextField(
                    value = symbolsText,
                    onValueChange = {
                        symbolsText = it
                        symbolsError = null
                        savedMsg = null
                    },
                    label = { Text("Koin (pisahkan koma)") },
                    singleLine = true,
                    isError = symbolsError != null,
                    supportingText = { Text(symbolsError ?: "Contoh: BTCUSDT,SOLUSDT (maks ${FeedRules.MAX_SYMBOLS} koin)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = limitText,
                    onValueChange = {
                        limitText = it.filter { c -> c.isDigit() }.take(4)
                        limitError = null
                        savedMsg = null
                    },
                    label = { Text("Limit transaksi per daftar") },
                    suffix = { Text("transaksi") },
                    singleLine = true,
                    isError = limitError != null,
                    supportingText = {
                        Text(limitError ?: "${FeedRules.MIN_LIMIT}–${FeedRules.MAX_LIMIT} (sama seperti BUFFER_SIZE di server pribadi)")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = spot, onCheckedChange = {
                        spot = it
                        marketError = null
                        savedMsg = null
                    })
                    Text("Trading (spot)", style = MaterialTheme.typography.bodyMedium)
                    Checkbox(checked = future, onCheckedChange = {
                        future = it
                        marketError = null
                        savedMsg = null
                    })
                    Text("Future", style = MaterialTheme.typography.bodyMedium)
                }
                marketError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Koneksi WebSocket: $previewCount (satu per koin per pasar).",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- pilihan 2: server sendiri
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    source = Source.SERVER
                    savedMsg = null
                }
            ) {
                RadioButton(selected = source == Source.SERVER, onClick = {
                    source = Source.SERVER
                    savedMsg = null
                })
                Column {
                    Text("Server sendiri", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Memakai alamat WebSocket di kolom di bawah.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = {
                    savedMsg = null
                    if (source == Source.BINANCE) {
                        val parsed = FeedRules.parseSymbols(symbolsText)
                        val limit = limitText.toIntOrNull()
                        symbolsError = parsed.error
                        limitError = if (limit == null || limit !in FeedRules.MIN_LIMIT..FeedRules.MAX_LIMIT) {
                            "Isi ${FeedRules.MIN_LIMIT}–${FeedRules.MAX_LIMIT}"
                        } else null
                        marketError = if (!spot && !future) "Pilih minimal satu pasar" else null
                        if (parsed.error == null && limit != null && limitError == null && marketError == null) {
                            val cfg = BinanceConfig(parsed.symbols, limit, spot, future)
                            Prefs.setBinanceConfig(ctx, cfg)
                            Prefs.setSource(ctx, Source.BINANCE)
                            activeSource = Source.BINANCE
                            symbolsText = parsed.symbols.joinToString(",")
                            SignalService.reload(ctx)
                            savedMsg = "Tersimpan: Binance langsung • ${parsed.symbols.joinToString(", ")} • " +
                                    "limit $limit • ${cfg.connectionCount} koneksi."
                        }
                    } else {
                        Prefs.setSource(ctx, Source.SERVER)
                        activeSource = Source.SERVER
                        SignalService.reload(ctx)
                        savedMsg = "Tersimpan: server sendiri."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simpan") }

            savedMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Sumber aktif: ${if (activeSource == Source.BINANCE) "Binance langsung" else "server sendiri"}" +
                        if (activeSource == Source.BINANCE && serviceOn && feedInfo.isNotEmpty()) "\n$feedInfo" else "",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Widget dan tab Analisis memakai koin pertama dan kedua dari daftar (bawaan BTC dan SOL). " +
                        "Bentuk data dibuat sama dengan API pribadi: harga, nilai USDT, jam WIB, BUY/SELL, terbaru di atas.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}