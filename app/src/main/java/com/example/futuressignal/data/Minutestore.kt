package com.example.futuressignal.data

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Menyimpan data per menit sebagai CSV, satu file per koin per pasar (ditambahkan baris demi baris):
 *   filesDir/menit/future_BTCUSDT_1m.csv, trade_BTCUSDT_1m.csv, ...
 * Baris hanya ditambah di akhir (tidak menulis ulang seluruh file), jadi ringan walau datanya berminggu-minggu.
 */
object MinuteStore {
    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "menit").apply { mkdirs() }

    private fun fileFor(ctx: Context, symbol: String, market: Market): File =
        File(dir(ctx), "${market.label.lowercase(Locale.US)}_${symbol}_1m.csv")

    @Synchronized
    fun append(ctx: Context, symbol: String, market: Market, header: String, line: String) {
        try {
            val f = fileFor(ctx, symbol, market)
            val isNew = !f.exists() || f.length() == 0L
            f.appendText((if (isNew) header + "\n" else "") + line + "\n")
        } catch (e: Exception) {
            // penyimpanan penuh / gagal tulis: baris ini dilewati, perekaman berlanjut
        }
    }

    fun files(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    fun totalBytes(ctx: Context): Long = files(ctx).sumOf { it.length() }

    @Synchronized
    fun clear(ctx: Context) {
        files(ctx).forEach { it.delete() }
    }
}