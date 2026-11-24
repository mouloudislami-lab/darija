package com.darija.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiTranslator(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * يأخذ أي رسالة (بالفرنسية، العرنسية، الدارجة المكسرة، أو العربية)
     * ويحولها إلى كلام بالدارجة المغربية الساهلة والمفهومة لشخص أمي.
     */
    suspend fun translateToDarija(rawText: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("مفتاح الذكاء الاصطناعي غير موجود"))
        }

        val prompt = """
            أنت مساعد ناطق بالدارجة المغربية.
            المرسل إليه شخص أمي لا يعرف القراءة.
            المطلوب: خذ الرسالة التالية واشرح أو قل ما تعنيه بالدارجة المغربية الشعبية السهلة جدا والمفهومة، كأنك صديق يتكلم معه مباشرة في الهاتف.
            
            قواعد صارمة:
            1. لا تضع أي مقدمة أو خاتمة ولا تقول 'الرسالة كتقول' أو 'المعنى هو'.
            2. انطق المضمون مباشرة بالدارجة المغربية الصافية بحروف عربية واضحة ومشكولة قدر الإمكان.
            3. إذا كانت الرسالة بالفرنسية أو العرنسية بالأرقام، فسر معناها بدقة وبساطة.
            4. إذا كانت تتضمن كود أو أرقام، اذكر الأرقام بالدارجة (مثلا: واحد زوج ثلاثة).
            
            الرسالة:
            $rawText
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody(mediaType))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                // Fallback to gemini-1.5-flash if 2.0-flash isn't enabled on the key
                val fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
                val fallbackReq = Request.Builder()
                    .url(fallbackUrl)
                    .post(jsonBody.toString().toRequestBody(mediaType))
                    .build()
                val fallbackResp = client.newCall(fallbackReq).execute()
                val fallbackBody = fallbackResp.body?.string() ?: ""

                if (!fallbackResp.isSuccessful) {
                    return@withContext Result.failure(Exception("فشل الاتصال بجيميني: ${response.code}"))
                }
                return@withContext parseGeminiResponse(fallbackBody)
            }

            return@withContext parseGeminiResponse(responseBody)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    private fun parseGeminiResponse(jsonString: String): Result<String> {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val text = parts.getJSONObject(0).getString("text")
                Result.success(text.trim())
            } else {
                Result.failure(Exception("لم يتم العثور على رد من الذكاء الاصطناعي"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
