package com.voisetranslator.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewConfiguration
import com.voisetranslator.R
import com.voisetranslator.ui.MainActivity
import kotlin.math.abs

/**
 * A draggable microphone button that floats above other apps, so a message can be dictated
 * while WhatsApp is on screen without hunting for the app in the launcher.
 */
class BubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (Settings.canDrawOverlays(this)) {
            showBubble()
        } else {
            // Permission was revoked while we were away; nothing to draw.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val view = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }

        view.findViewById<View>(R.id.bubble_button).setOnTouchListener(DragToMoveListener())
        manager.addView(view, layoutParams)
        bubble = view
    }

    /** Distinguishes a tap (open the app) from a drag (reposition the bubble). */
    private inner class DragToMoveListener : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                touchX = event.rawX
                touchY = event.rawY
                dragged = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                if (dragged) {
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(bubble, layoutParams) }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    view.performClick()
                    openDictation()
                }
                true
            }

            else -> false
        }
    }

    private fun openDictation() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_START_DICTATION, true)
            }
        )
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN,
            )
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(R.drawable.ic_bubble_mic)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Скрыть кнопку", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        windowManager = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voise_bubble"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.voisetranslator.STOP_BUBBLE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, BubbleService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
