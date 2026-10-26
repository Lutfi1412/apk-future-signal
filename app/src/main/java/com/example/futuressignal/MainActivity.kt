package com.example.futuressignal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.futuressignal.data.LogStore
import com.example.futuressignal.data.Prefs
import com.example.futuressignal.data.ServiceState
import com.example.futuressignal.service.SignalService
import com.example.futuressignal.signal.SignalNotifier
import com.example.futuressignal.ui.AnalysisScreen
import com.example.futuressignal.ui.LogDetailScreen
import com.example.futuressignal.ui.LogListScreen
import com.example.futuressignal.ui.MainScreen

/** Halaman aplikasi (navigasi sederhana tanpa library tambahan). */
sealed interface Screen {
    data object Home : Screen
    data object Log : Screen
    data object Analysis : Screen
    data class Detail(val id: Long) : Screen
}

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        LogStore.preload(this)

        // Ingat status ON/OFF terakhir
        val wasOn = Prefs.isEnabled(this)
        ServiceState.enabled.value = wasOn
        ServiceState.soundEnabled.value = Prefs.soundEnabled(this)
        SignalNotifier.createChannels(this)
        SignalNotifier.prepare(this)
        if (wasOn) SignalService.start(this)   // aman dipanggil berulang

        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BackHandler(enabled = screen != Screen.Home) {
                        screen = if (screen is Screen.Detail) Screen.Log else Screen.Home
                    }
                    when (val s = screen) {
                        Screen.Home -> MainScreen(
                            onOpenLog = { screen = Screen.Log },
                            onOpenAnalysis = { screen = Screen.Analysis }
                        )
                        Screen.Analysis -> AnalysisScreen(onBack = { screen = Screen.Home })
                        Screen.Log -> LogListScreen(
                            onOpen = { id -> screen = Screen.Detail(id) },
                            onBack = { screen = Screen.Home }
                        )
                        is Screen.Detail -> LogDetailScreen(
                            id = s.id,
                            onBack = { screen = Screen.Log }
                        )
                    }
                }
            }
        }
    }

    // Notifikasi diketuk saat aplikasi sudah terbuka (launchMode singleTop)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Kalau dibuka dari notifikasi sinyal, langsung tampilkan detail log-nya. */
    private fun handleIntent(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_LOG_ID, -1L) ?: -1L
        if (id != -1L) {
            screen = Screen.Detail(id)
            i?.removeExtra(EXTRA_LOG_ID)
        }
    }

    companion object {
        const val EXTRA_LOG_ID = "log_id"
    }
}