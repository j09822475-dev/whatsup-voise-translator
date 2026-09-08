package com.voisetranslator.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A voice offered by the device's TTS engine, in a form the UI can show. */
data class VoiceOption(
    val name: String,
    val label: String,
    val needsNetwork: Boolean,
)

class TtsSynthesisException(message: String) : Exception(message)

/**
 * Wraps the system text-to-speech engine — the same one Android uses for Talkback and
 * "read aloud", so no extra API keys or quotas. Used both to preview a voice and to render
 * the outgoing message to a file.
 */
class TtsEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Unit>? = null

    /** Boots the engine (once) and resolves when it can speak English. */
    suspend fun ensureReady(): TextToSpeech {
        val existing = tts
        val existingReady = ready
        if (existing != null && existingReady != null) {
            existingReady.await()
            return existing
        }

        val deferred = CompletableDeferred<Unit>()
        ready = deferred
        val engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                deferred.complete(Unit)
            } else {
                deferred.completeExceptionally(
                    TtsSynthesisException("Движок синтеза речи не запустился. Проверьте, что в системе установлен Speech Services.")
                )
            }
        }
        tts = engine

        try {
            deferred.await()
        } catch (e: Throwable) {
            shutdown()
            throw e
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        when (engine.setLanguage(Locale.US)) {
            TextToSpeech.LANG_MISSING_DATA ->
                throw TtsSynthesisException("Для английского языка не установлены голосовые данные. Откройте настройки синтеза речи и скачайте English (US).")
            TextToSpeech.LANG_NOT_SUPPORTED ->
                throw TtsSynthesisException("Установленный движок синтеза речи не поддерживает английский язык.")
        }
        return engine
    }

    /** English voices the installed engine can offer, best-labelled first. */
    suspend fun englishVoices(): List<VoiceOption> {
        val engine = ensureReady()
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filter { it.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) }
            .filterNot { it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .map { voice ->
                VoiceOption(
                    name = voice.name,
                    label = buildLabel(voice),
                    needsNetwork = voice.isNetworkConnectionRequired,
                )
            }
    }

    private fun buildLabel(voice: Voice): String {
        val country = voice.locale.getDisplayCountry(RUSSIAN_LOCALE).takeIf { it.isNotBlank() }
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "очень высокое"
            voice.quality >= Voice.QUALITY_HIGH -> "высокое"
            voice.quality >= Voice.QUALITY_NORMAL -> "обычное"
            else -> "низкое"
        }
        return listOfNotNull(voice.name, country, "качество: $quality").joinToString(" · ")
    }

    /** Plays [text] out loud so the user can judge the voice before sending. */
    suspend fun preview(text: String, voiceName: String, rate: Float, pitch: Float) {
        val engine = applyVoice(voiceName, rate, pitch)
        engine.stop()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE)
    }

    fun stopPreview() {
        runCatching { tts?.stop() }
    }

    /**
     * Renders [text] into [target] as a PCM WAV file and suspends until the engine is done.
     */
    suspend fun synthesizeToWav(
        text: String,
        target: File,
        voiceName: String,
        rate: Float,
        pitch: Float,
    ): File = withContext(Dispatchers.IO) {
        val engine = applyVoice(voiceName, rate, pitch)
        val utteranceId = "voise-" + System.nanoTime()
        val done = CompletableDeferred<Unit>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId) done.complete(Unit)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    done.completeExceptionally(TtsSynthesisException("Не удалось синтезировать речь"))
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    done.completeExceptionally(
                        TtsSynthesisException("Не удалось синтезировать речь (код $errorCode)")
                    )
                }
            }
        })

        target.parentFile?.mkdirs()
        val queued = engine.synthesizeToFile(text, null, target, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            throw TtsSynthesisException("Движок синтеза речи отказался обрабатывать текст")
        }

        done.await()

        if (!target.exists() || target.length() <= WAV_HEADER_BYTES) {
            throw TtsSynthesisException("Синтез вернул пустой файл")
        }
        target
    }

    private suspend fun applyVoice(voiceName: String, rate: Float, pitch: Float): TextToSpeech {
        val engine = ensureReady()
        if (voiceName.isNotBlank()) {
            runCatching { engine.voices }.getOrNull()
                ?.firstOrNull { it.name == voiceName }
                ?.let { engine.setVoice(it) }
        }
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        return engine
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = null
    }

    private companion object {
        const val PREVIEW_UTTERANCE = "voise-preview"

        val RUSSIAN_LOCALE: Locale = Locale.forLanguageTag("ru")

        /** A WAV file with nothing but a RIFF header is 44 bytes. */
        const val WAV_HEADER_BYTES = 44L
    }
}
