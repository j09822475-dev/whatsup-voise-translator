package com.voisetranslator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voisetranslator.ui.theme.VoiseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true && pendingDictation) {
            pendingDictation = false
            viewModel.startDictation()
        }
    }

    /** Set when the bubble asked for dictation but the mic permission was still missing. */
    private var pendingDictation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()

        setContent {
            VoiseTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    viewModel.launches.collect { intent -> startActivity(intent) }
                }

                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        viewModel = viewModel,
                        onBack = { showSettings = false },
                    )
                } else {
                    MainScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenSettings = { showSettings = true },
                        onRequestMic = { startDictationWithPermission() },
                    )
                }
            }
        }

        if (intent?.getBooleanExtra(EXTRA_START_DICTATION, false) == true) {
            startDictationWithPermission()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_DICTATION, false)) {
            startDictationWithPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // WhatsApp may have been installed, or the accessibility service switched on, while away.
        viewModel.refreshEnvironment()
    }

    private fun startDictationWithPermission() {
        if (hasMicPermission()) {
            viewModel.startDictation()
        } else {
            pendingDictation = true
            ensurePermissions()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val wanted = buildList {
            if (!hasMicPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }

    companion object {
        const val EXTRA_START_DICTATION = "com.voisetranslator.START_DICTATION"
    }
}
