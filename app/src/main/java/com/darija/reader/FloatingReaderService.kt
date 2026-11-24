package com.darija.reader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingReaderService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleImage: ImageView? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var ttsPlayer: EdgeTtsPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var isBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("darija_prefs", Context.MODE_PRIVATE)
        ttsPlayer = EdgeTtsPlayer(this)

        startForegroundNotification()
        setupFloatingBubble()
    }

    private fun startForegroundNotification() {
        val channelId = "floating_reader_channel"
        val channelName = "خدمة قارئ الدارجة"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("قارئ الدارجة قيد التشغيل")
            .setContentText("اضغط على أيقونة الصوت العائمة لقراءة الرسائل المنسوخة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 350
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        bubbleImage = floatingView?.findViewById(R.id.bubble_image)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                        isClick = false
                    }

                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingView, params)
    }

    private fun onBubbleClicked() {
        if (isBusy) {
            ttsPlayer.stop()
            setBubbleState(BubbleState.NORMAL)
            isBusy = false
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip() ||
            clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true &&
            clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) != true
        ) {
            Toast.makeText(this, "انسخ أي رسالة أولا من واتساب أو إنستغرام", Toast.LENGTH_SHORT).show()
            return
        }

        val item = clipboard.primaryClip?.getItemAt(0)
        val textToRead = item?.text?.toString()?.trim()

        if (textToRead.isNullOrBlank()) {
            Toast.makeText(this, "انسخ أي رسالة أولا من واتساب أو إنستغرام", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "المرجو إدخال مفتاح الذكاء الاصطناعي في التطبيق أولا", Toast.LENGTH_LONG).show()
            return
        }

        processAndSpeak(textToRead, apiKey)
    }

    private fun processAndSpeak(text: String, apiKey: String) {
        isBusy = true
        setBubbleState(BubbleState.PROCESSING)

        serviceScope.launch {
            val translator = GeminiTranslator(apiKey)
            val result = translator.translateToDarija(text)

            result.onSuccess { darijaText ->
                setBubbleState(BubbleState.SPEAKING)
                ttsPlayer.speak(
                    text = darijaText,
                    onStart = {
                        setBubbleState(BubbleState.SPEAKING)
                    },
                    onDone = {
                        setBubbleState(BubbleState.NORMAL)
                        isBusy = false
                    },
                    onError = {
                        Toast.makeText(this@FloatingReaderService, "وقع خطأ في نطق الصوت", Toast.LENGTH_SHORT).show()
                        setBubbleState(BubbleState.NORMAL)
                        isBusy = false
                    }
                )
            }.onFailure { err ->
                Toast.makeText(this@FloatingReaderService, err.message ?: "وقع خطأ", Toast.LENGTH_SHORT).show()
                setBubbleState(BubbleState.NORMAL)
                isBusy = false
            }
        }
    }

    private enum class BubbleState {
        NORMAL, PROCESSING, SPEAKING
    }

    private fun setBubbleState(state: BubbleState) {
        when (state) {
            BubbleState.NORMAL -> {
                bubbleImage?.setBackgroundResource(R.drawable.bg_bubble)
            }
            BubbleState.PROCESSING -> {
                bubbleImage?.setBackgroundResource(R.drawable.bg_bubble_processing)
            }
            BubbleState.SPEAKING -> {
                bubbleImage?.setBackgroundResource(R.drawable.bg_bubble_speaking)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsPlayer.destroy()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
    }
}
