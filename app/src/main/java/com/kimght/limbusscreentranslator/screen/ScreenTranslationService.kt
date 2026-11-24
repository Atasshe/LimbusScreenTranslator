package com.kimght.limbusscreentranslator.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kimght.limbusscreentranslator.MainActivity
import com.kimght.limbusscreentranslator.R
import com.kimght.limbusscreentranslator.core.i18n.localizedTo
import com.kimght.limbusscreentranslator.data.datastore.SettingsRepository
import com.kimght.limbusscreentranslator.data.repository.ScreenPackNeedsUpdate
import com.kimght.limbusscreentranslator.data.repository.ScreenPackUnavailable
import com.kimght.limbusscreentranslator.data.repository.ScreenTranslationRepository
import com.kimght.limbusscreentranslator.overlay.OverlayService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
internal class ScreenTranslationService : Service() {
    @Inject lateinit var repository: ScreenTranslationRepository
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlay: ScreenTranslationView? = null
    private var capture: ScreenFrameCapture? = null
    private var notificationContext: Context? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (capture != null) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT < 34 || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val permission = intent?.getParcelableExtra(CONSENT, Intent::class.java)
        if (permission == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCapture(permission)
        } catch (_: RuntimeException) {
            fail(R.string.screen_capture_failed)
        }
        return START_NOT_STICKY
    }

    @RequiresApi(34)
    private fun startCapture(permission: Intent) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.screen_translation), NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(this, R.string.screen_preparing),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = checkNotNull(projectionManager.getMediaProjection(Activity.RESULT_OK, permission))

        val displayManager = getSystemService(DisplayManager::class.java)
        val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        val windowContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        val layer = ScreenTranslationView(windowContext)
        val reader = ScreenFrameCapture(
            projection = projection,
            densityDpi = windowContext.resources.configuration.densityDpi,
            canRender = ::fillsLandscapeDisplay,
            onFrame = layer::show,
            onCaptureStopped = { stopSelf() },
            onFailure = { fail(R.string.screen_capture_failed) },
        )
        capture = reader
        try {
            // Only one overlay may sit above the game while screen translation is running.
            OverlayService.stop(this)
            windowManager.addView(layer, overlayParams())
            overlay = layer
            val bounds = windowManager.maximumWindowMetrics.bounds
            reader.start(bounds.width(), bounds.height())
            _running.value = true
        } catch (e: RuntimeException) {
            reader.close()
            throw e
        }

        scope.launch {
            notificationContext = localizedTo(settings.settings.first().uiLanguage)
            try {
                repository.activePack.collectLatest { pack ->
                    reader.setIndex(null)
                    val context = notificationContext ?: this@ScreenTranslationService
                    manager.notify(NOTIFICATION_ID, notification(context, R.string.screen_preparing))
                    val index = repository.load(pack ?: throw ScreenPackUnavailable())
                    reader.setIndex(index)
                    manager.notify(NOTIFICATION_ID, notification(context, R.string.screen_running))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ScreenPackNeedsUpdate) {
                fail(R.string.screen_update_pack)
            } catch (_: Exception) {
                fail(R.string.screen_pack_failed)
            }
        }
    }

    @RequiresApi(34)
    private fun fillsLandscapeDisplay(width: Int, height: Int): Boolean {
        val bounds = windowManager.maximumWindowMetrics.bounds
        return width > height &&
            abs(width - bounds.width()) <= 4 && abs(height - bounds.height()) <= 4
    }

    @RequiresApi(34)
    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val input = getSystemService(InputManager::class.java)
        alpha = minOf(0.80f, input.maximumObscuringOpacityForTouch)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= 34) capture?.updateVisibility()
    }

    override fun onDestroy() {
        scope.cancel()
        if (Build.VERSION.SDK_INT >= 34) capture?.close()
        capture = null
        overlay?.let { windowManager.removeViewImmediate(it) }
        overlay = null
        _running.value = false
        super.onDestroy()
    }

    private fun fail(@StringRes text: Int) {
        val context = notificationContext ?: this
        Toast.makeText(context, context.getString(text), Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun notification(context: Context, @StringRes message: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenTranslationService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.screen_translation))
            .setContentText(context.getString(message))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.screen_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "screen_translation"
        private const val NOTIFICATION_ID = 0x10C
        private const val CONSENT = "projection_consent"
        private const val STOP = "stop_screen_translation"
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        fun start(context: Context, permission: Intent) {
            val intent = Intent(context, ScreenTranslationService::class.java).putExtra(CONSENT, permission)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTranslationService::class.java))
        }
    }
}
