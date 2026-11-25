package com.pstranslator.tv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Persistent System Floating Overlay Service for Android TV.
 * Displays a fullscreen, transparent, click-through WebView loading http://<phone-ip>:8791/tv
 * so Thai subtitles & GTA 6 popups float smoothly on top of HDMI inputs (PS5) or other apps.
 */
class TvOverlayService : Service() {
    companion object {
        const val EXTRA_URL = "overlay_url"
        private const val CHANNEL_ID = "ps_translator_tv_overlay"
        private const val NOTIF_ID = 8791

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var overlayWebView: WebView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotification()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayWebView == null) {
            createOverlayWebView(url)
        } else {
            overlayWebView?.loadUrl(url)
        }
        return START_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createOverlayWebView(url: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val wv = WebView(applicationContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            loadUrl(url)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(wv, params)
            overlayWebView = wv
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "PS Translator TV Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
            val notif = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PS Translator TV")
                .setContentText("กำลังแสดงซับไตเติ้ลลอยทับหน้าจอทีวี")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
            startForeground(NOTIF_ID, notif)
        } else {
            @Suppress("DEPRECATION")
            val notif = Notification.Builder(this)
                .setContentTitle("PS Translator TV")
                .setContentText("กำลังแสดงซับไตเติ้ลลอยทับหน้าจอทีวี")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            overlayWebView?.let { wv ->
                windowManager?.removeView(wv)
                wv.destroy()
            }
        } catch (_: Exception) {}
        overlayWebView = null
        super.onDestroy()
    }
}
