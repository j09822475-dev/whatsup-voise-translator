package com.voisetranslator.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Translation through the Gemini REST API. Better than the on-device model with idioms,
 * slang and messages that need a natural spoken register — which is what a voice message is.
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String,
) : Translator {

    private val client = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw TranslationException("Не указан ключ Gemini API — добавьте его в настройках")
        }

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                ),
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 2048),
            )
        }

        val request = Request.Builder()
            .url("$ENDPOINT/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TranslationException(describeHttpError(response.code, raw))
                }
                raw
            }
        } catch (e: IOException) {
            throw TranslationException("Не удалось связаться с Gemini: ${e.message}", e)
        }

        extractText(body)
    }

    private fun extractText(raw: String): String {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw TranslationException("Gemini вернул неожиданный ответ", e)
        }

        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw TranslationException(
                root.optJSONObject("promptFeedback")?.optString("blockReason")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "Gemini отклонил запрос: $it" }
                    ?: "Gemini не вернул перевод"
            )

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            for (i in 0 until (parts?.length() ?: 0)) {
                append(parts!!.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()

        if (text.isEmpty()) {
            // MAX_TOKENS / SAFETY land here with an empty parts array.
            val reason = candidate.optString("finishReason").takeIf { it.isNotBlank() }
            throw TranslationException(
                reason?.let { "Gemini прервал ответ ($it)" } ?: "Gemini вернул пустой перевод"
            )
        }
        return text
    }

    private fun describeHttpError(code: Int, raw: String): String {
        val apiMessage = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return when (code) {
            400 -> apiMessage ?: "Gemini отклонил запрос (400)"
            401, 403 -> "Ключ Gemini API недействителен или без доступа к модели"
            404 -> "Модель \"$model\" недоступна для этого ключа"
            429 -> "Превышен лимит запросов Gemini — попробуйте позже"
            in 500..599 -> "Gemini временно недоступен ($code)"
            else -> apiMessage ?: "Gemini вернул ошибку $code"
        }
    }

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val SYSTEM_PROMPT = """
            You translate Russian speech into English.

            The input is a raw speech-to-text transcript of a voice message, so it has no
            punctuation, may contain filler words, and may be lightly misrecognised. Infer the
            intended meaning and produce natural spoken English that a native speaker would
            actually say out loud in a voice message.

            Rules:
            - Output only the English translation. No preamble, no quotes, no notes.
            - Add normal punctuation and capitalisation.
            - Keep the speaker's register: casual stays casual, formal stays formal.
            - Drop filler words ("ну", "вот", "короче") when they carry no meaning.
            - Keep names, numbers and addresses exactly as spoken.
            - If the input is already English, return it cleaned up rather than translated.
        """.trimIndent()
    }
}
