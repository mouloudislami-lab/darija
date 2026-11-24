package com.darija.reader

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class EdgeTtsPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var nativeTts: TextToSpeech? = null
    private var isNativeTtsReady = false

    init {
        // تجهيز قارئ الصوت المدمج في الهاتف كاحتياط
        nativeTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = nativeTts?.setLanguage(Locale("ar", "MA"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    nativeTts?.setLanguage(Locale("ar"))
                }
                isNativeTtsReady = true
            }
        }
    }

    /**
     * تشغيل الصوت المغربي عالي الجودة (صوت جمال المغربي العصبي)
     * وفي حالة تعذر الاتصال، يتم الرجوع للصوت المحلي للهاتف.
     */
    suspend fun speak(
        text: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.Main) {
        stop()

        try {
            // محاولة جلب الصوت السحابي المغربي المتقن
            val audioData = fetchEdgeTtsAudio(text)
            if (audioData != null && audioData.isNotEmpty()) {
                playAudioBytes(audioData, onStart, onDone, onError)
            } else {
                // الرجوع للصوت المدمج
                speakNative(text, onStart, onDone, onError)
            }
        } catch (e: Exception) {
            // في حالة أي خطأ نلجأ للصوت المدمج
            speakNative(text, onStart, onDone, onError)
        }
    }

    private suspend fun fetchEdgeTtsAudio(text: String): ByteArray? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val wssUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
            val request = Request.Builder()
                .url(wssUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .build()

            val audioBuffer = ByteArrayOutputStream()
            val requestId = UUID.randomUUID().toString().replace("-", "")

            val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val dateStr = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).format(Date())

                    // 1. إرسال ضبط إعدادات الصوت
                    val configMsg = "X-Timestamp:$dateStr\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    webSocket.send(configMsg)

                    // 2. إرسال نص الدارجة بصوت جمال المغربي
                    val safeText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ar-MA'>" +
                            "<voice name='ar-MA-JamalNeural'>$safeText</voice>" +
                            "</speak>"

                    val ssmlMsg = "X-RequestId:$requestId\r\nX-Timestamp:$dateStr\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml"
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val raw = bytes.toByteArray()
                    if (raw.size > 2) {
                        val headerLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                        if (raw.size > headerLen + 2) {
                            audioBuffer.write(raw, headerLen + 2, raw.size - (headerLen + 2))
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, textMsg: String) {
                    if (textMsg.contains("Path:turn.end")) {
                        webSocket.close(1000, "Done")
                        if (continuation.isActive) {
                            continuation.resume(audioBuffer.toByteArray())
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) {
                        continuation.resume(audioBuffer.toByteArray())
                    }
                }
            })

            continuation.invokeOnCancellation {
                webSocket.cancel()
            }
        }
    }

    private fun playAudioBytes(
        bytes: ByteArray,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val tempFile = File(context.cacheDir, "darija_speech_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { it.write(bytes) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener {
                    onStart()
                    start()
                }
                setOnCompletionListener {
                    onDone()
                    tempFile.delete()
                    release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    onError("خطأ في تشغيل الصوت")
                    tempFile.delete()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            onError(e.message ?: "خطأ غير معروف")
        }
    }

    private fun speakNative(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isNativeTtsReady || nativeTts == null) {
            onError("محرك الصوت غير جاهز")
            return
        }

        onStart()
        nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "darija_tts_id")
        // بعد انتهاء النطق التقديري
        val estimatedDuration = (text.length * 90L).coerceAtLeast(1500L)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            onDone()
        }, estimatedDuration)
    }

    fun stop() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null

            nativeTts?.stop()
        } catch (_: Exception) {}
    }

    fun destroy() {
        stop()
        nativeTts?.shutdown()
        nativeTts = null
    }
}
