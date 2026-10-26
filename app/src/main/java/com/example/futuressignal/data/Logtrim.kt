package com.example.futuressignal.data

/** Logika kapasitas log tanpa dependensi Android (supaya mudah diuji). */
internal object LogTrim {

    /** Ringkas satu log: hanya transaksi terbaru yang disimpan (cukup untuk harga di analisis). */
    fun slim(e: LogEntry): LogEntry =
        if (e.trades.size > 1) e.copy(trades = e.trades.take(1), stats = e.stats ?: SignalStats.compute(e.trades)) else e

    /** Log pada indeks >= fullLimit (yang lebih lama) diringkas. Daftar diurutkan terbaru di atas. */
    fun slimOld(list: List<LogEntry>, fullLimit: Int): List<LogEntry> =
        if (list.size <= fullLimit) list
        else list.mapIndexed { i, e -> if (i >= fullLimit) slim(e) else e }

    /** Potong ke kapasitas (cap = null berarti tanpa batas). Yang dibuang adalah yang paling lama. */
    fun capped(list: List<LogEntry>, cap: Int?): List<LogEntry> =
        if (cap != null && list.size > cap) list.subList(0, cap).toList() else list

    /** Tambah log baru di paling atas, ringkas yang baru melewati batas detail, lalu potong sesuai kapasitas. */
    fun prepend(current: List<LogEntry>, e: LogEntry, cap: Int?, fullLimit: Int): List<LogEntry> {
        val list = ArrayList<LogEntry>(current.size + 1)
        list.add(e)
        list.addAll(current)
        if (list.size > fullLimit) list[fullLimit] = slim(list[fullLimit])
        return capped(list, cap)
    }
}