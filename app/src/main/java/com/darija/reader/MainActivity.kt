package com.darija.reader

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.darija.reader.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var ttsPlayer: EdgeTtsPlayer
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            startFloatingService()
        } else {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Continue regardless of notification permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("darija_prefs", Context.MODE_PRIVATE)
        ttsPlayer = EdgeTtsPlayer(this)

        loadSavedData()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        updateFloatingButtonState()
    }

    private fun loadSavedData() {
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        binding.etApiKey.setText(savedKey)
    }

    private fun setupListeners() {
        // حفظ مفتاح الذكاء الاصطناعي
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            prefs.edit().putString("gemini_api_key", key).apply()
            Toast.makeText(this, R.string.saved_successfully, Toast.LENGTH_SHORT).show()
        }

        // تشغيل أو إيقاف الزر العائم
        binding.btnToggleFloating.setOnClickListener {
            if (isServiceRunning(FloatingReaderService::class.java)) {
                stopFloatingService()
            } else {
                if (!hasOverlayPermission()) {
                    requestOverlayPermission()
                } else {
                    startFloatingService()
                }
            }
        }

        // زر تجربة الصوت والنص مباشرة
        binding.btnTestSpeak.setOnClickListener {
            val text = binding.etTestText.text?.toString()?.trim() ?: ""
            if (text.isBlank()) {
                Toast.makeText(this, "اكتب شي نص لتجربته", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "المرجو إدخال مفتاح الذكاء الاصطناعي أولا", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // حفظ المفتاح تلقائيا
            prefs.edit().putString("gemini_api_key", key).apply()

            showStatus("جاري الفهم والتحويل للدارجة...")

            mainScope.launch {
                val translator = GeminiTranslator(key)
                val result = translator.translateToDarija(text)

                result.onSuccess { darijaText ->
                    showStatus("الدارجة: $darijaText\n\nجاري النطق بصوت مغربي...")
                    ttsPlayer.speak(
                        text = darijaText,
                        onStart = {
                            showStatus("جاري قراءة الرسالة...")
                        },
                        onDone = {
                            showStatus("تمت القراءة بنجاح!")
                        },
                        onError = { err ->
                            showStatus("خطأ: $err")
                        }
                    )
                }.onFailure { err ->
                    showStatus("خطأ في الاتصال: ${err.message}")
                }
            }
        }
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = msg
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingReaderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateFloatingButtonState()
        Toast.makeText(this, "تم تشغيل الزر العائم! توجه إلى واتساب أو إنستغرام", Toast.LENGTH_LONG).show()
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingReaderService::class.java)
        stopService(intent)
        updateFloatingButtonState()
        Toast.makeText(this, "تم إيقاف الزر العائم", Toast.LENGTH_SHORT).show()
    }

    private fun updateFloatingButtonState() {
        val running = isServiceRunning(FloatingReaderService::class.java)
        if (running) {
            binding.btnToggleFloating.setText(R.string.stop_floating_btn)
            binding.btnToggleFloating.setBackgroundColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            binding.btnToggleFloating.setText(R.string.start_floating_btn)
            binding.btnToggleFloating.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        updateFloatingButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsPlayer.destroy()
    }
}
