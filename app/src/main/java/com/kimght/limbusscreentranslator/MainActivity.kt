package com.kimght.limbusscreentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kimght.limbusscreentranslator.core.i18n.ProvideUiLanguage
import com.kimght.limbusscreentranslator.core.i18n.localizedTo
import com.kimght.limbusscreentranslator.data.datastore.SettingsRepository
import com.kimght.limbusscreentranslator.data.datastore.Settings as AppSettings
import com.kimght.limbusscreentranslator.feature.navigation.ManagerApp
import com.kimght.limbusscreentranslator.overlay.OverlayLauncher
import com.kimght.limbusscreentranslator.screen.ScreenTranslationService
import com.kimght.limbusscreentranslator.ui.theme.LimbusScreenTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var overlayLauncher: OverlayLauncher

    @Volatile
    private var currentLanguage: String = AppSettings.defaultUiLanguage()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            lifecycleScope.launch { overlayLauncher.start() }
        } else {
            Toast.makeText(
                this,
                localizedTo(currentLanguage).getString(R.string.overlay_permission_required),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { startOverlayService() }

    private val screenCaptureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val consent = result.data
        if (result.resultCode == Activity.RESULT_OK && consent != null) {
            try {
                ScreenTranslationService.start(this, consent)
            } catch (_: IllegalStateException) {
                showScreenMessage(R.string.screen_capture_failed)
            } catch (_: SecurityException) {
                showScreenMessage(R.string.screen_capture_failed)
            }
        }
    }

    private val screenOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCapture()
        } else {
            showScreenMessage(R.string.overlay_permission_required)
        }
    }

    private val screenNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ensureScreenOverlayPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            settings.settings.collect { currentLanguage = it.uiLanguage }
        }
        setContent {
            val languageFlow = remember {
                settings.settings.map { it.uiLanguage }.distinctUntilChanged()
            }
            val language by languageFlow.collectAsStateWithLifecycle(
                initialValue = AppSettings.defaultUiLanguage(),
            )
            val screenTranslationRunning by ScreenTranslationService.running.collectAsStateWithLifecycle()
            LimbusScreenTranslatorTheme {
                ProvideUiLanguage(language) {
                    ManagerApp(
                        onOpenOverlay = ::launchOverlay,
                        onCloseOverlay = { overlayLauncher.stop() },
                        screenTranslationRunning = screenTranslationRunning,
                        onToggleScreenTranslation = ::toggleScreenTranslation,
                    )
                }
            }
        }
    }

    private fun toggleScreenTranslation() {
        if (Build.VERSION.SDK_INT < 34) return
        if (ScreenTranslationService.running.value) {
            ScreenTranslationService.stop(this)
        } else if (needsNotificationPermission()) {
            screenNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureScreenOverlayPermission()
        }
    }

    private fun ensureScreenOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCapture()
        } else {
            screenOverlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            )
        }
    }

    private fun requestScreenCapture() {
        val projection = getSystemService(MediaProjectionManager::class.java)
        screenCaptureConsent.launch(projection.createScreenCaptureIntent())
    }

    private fun showScreenMessage(@androidx.annotation.StringRes id: Int) {
        Toast.makeText(this, localizedTo(currentLanguage).getString(id), Toast.LENGTH_LONG).show()
    }

    private fun launchOverlay() {
        if (needsNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOverlayService()
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun startOverlayService() {
        if (Settings.canDrawOverlays(this)) {
            lifecycleScope.launch { overlayLauncher.start() }
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            )
        }
    }
}
