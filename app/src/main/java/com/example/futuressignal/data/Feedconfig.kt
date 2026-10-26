package com.example.futuressignal.data

/** Sumber data sinyal. */
object Source {
    const val SERVER = "server"      // server pribadi (alamat WebSocket di layar utama)
    const val BINANCE = "binance"    // WebSocket publik Binance, langsung dari aplikasi
}

/** Pengaturan untuk sumber Binance langsung. */
data class BinanceConfig(
    val symbols: List<String>,   // mis. BTCUSDT, SOLUSDT
    val limit: Int,              // jumlah transaksi per daftar (sama seperti BUFFER_SIZE di server pribadi)
    val spot: Boolean,           // "Trade"
    val future: Boolean          // "Future"
) {
    /** Satu koneksi WebSocket per koin per pasar (bawaan: 2 koin x 2 pasar = 4 koneksi). */
    val connectionCount: Int
        get() = symbols.size * ((if (spot) 1 else 0) + (if (future) 1 else 0))
}

data class ParseResult(val symbols: List<String>, val error: String?)

object FeedRules {
    const val MAX_SYMBOLS = 6
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 1000          // batas REST Binance untuk /trades
    const val DEFAULT_LIMIT = 50
    val DEFAULT_SYMBOLS = listOf("BTCUSDT", "SOLUSDT")

    private val SYMBOL_REGEX = Regex("^[A-Z0-9]{3,20}$")

    /** Pisahkan input (koma/spasi/titik koma/baris baru), rapikan ke huruf besar, buang duplikat, lalu validasi. */
    fun parseSymbols(input: String): ParseResult {
        val list = input.split(',', ' ', ';', '\n')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (list.isEmpty()) return ParseResult(emptyList(), "Isi minimal 1 koin")
        if (list.size > MAX_SYMBOLS) return ParseResult(emptyList(), "Maksimal $MAX_SYMBOLS koin")
        val bad = list.firstOrNull { !SYMBOL_REGEX.matches(it) }
        if (bad != null) return ParseResult(emptyList(), "Simbol tidak valid: $bad")
        return ParseResult(list, null)
    }
}

/** Ringkasan status koneksi Binance langsung. */
data class FeedStatus(val connected: Int, val total: Int, val errors: List<String>) {
    fun describe(): String {
        val base = "Binance langsung: $connected/$total koneksi tersambung"
        return if (errors.isEmpty()) base else "$base • ${errors.first()}" +
                if (errors.size > 1) " (+${errors.size - 1} masalah lain)" else ""
    }
}