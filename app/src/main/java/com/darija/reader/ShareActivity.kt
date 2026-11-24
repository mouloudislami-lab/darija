package com.darija.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ShareActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var ttsPlayer: EdgeTtsPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsPlayer = EdgeTtsPlayer(this)

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val prefs = getSharedPreferences("darija_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""

                if (apiKey.isBlank()) {
                    Toast.makeText(this, "المرجو إدخال مفتاح الذكاء الاصطناعي في التطبيق أولا", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }

                Toast.makeText(this, "جاري تحويل الرسالة للدارجة...", Toast.LENGTH_SHORT).show()

                activityScope.launch {
                    val translator = GeminiTranslator(apiKey)
                    val result = translator.translateToDarija(sharedText)

                    result.onSuccess { darijaText ->
                        ttsPlayer.speak(
                            text = darijaText,
                            onStart = {},
                            onDone = {
                                finish()
                            },
                            onError = {
                                finish()
                            }
                        )
                    }.onFailure {
                        Toast.makeText(this@ShareActivity, "تعذر قراءة الرسالة", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsPlayer.destroy()
    }
}
