package com.voisetranslator.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** What the recogniser reports while the user is dictating. */
sealed interface SpeechEvent {
    data object ReadyForSpeech : SpeechEvent

    /** Mic level in roughly 0..1, for the animated ring around the button. */
    data class Level(val rms: Float) : SpeechEvent

    data class Partial(val text: String) : SpeechEvent

    data class Final(val text: String) : SpeechEvent

    data class Failed(val message: String) : SpeechEvent
}

/**
 * Thin coroutine wrapper over [SpeechRecognizer].
 *
 * The platform recogniser has to be created and driven from the main thread, hence the
 * `flowOn(Dispatchers.Main)`. Collecting [listen] starts a single dictation; cancelling the
 * collection stops and releases the recogniser.
 */
class SpeechRecognizerController(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(languageTag: String = "ru-RU"): Flow<SpeechEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Failed("На устройстве нет службы распознавания речи"))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        // Guards against emitting a Final twice: onResults may arrive after onError.
        var finished = false

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.ReadyForSpeech)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // The platform reports roughly -2..10 dB; squash it into 0..1.
                trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = results.firstResult()
                if (text.isNullOrBlank()) {
                    trySend(SpeechEvent.Failed("Не удалось разобрать речь"))
                } else {
                    trySend(SpeechEvent.Final(text))
                }
                close()
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                trySend(SpeechEvent.Failed(describeError(error)))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer.startListening(intent)

        awaitClose {
            runCatching {
                recognizer.stopListening()
                recognizer.destroy()
            }
        }
    }.flowOn(Dispatchers.Main)

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука"
        SpeechRecognizer.ERROR_CLIENT -> "Распознавание прервано"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        SpeechRecognizer.ERROR_NETWORK -> "Нет сети для распознавания"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сеть не ответила вовремя"
        SpeechRecognizer.ERROR_NO_MATCH -> "Ничего не распознано — попробуйте ещё раз"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, подождите секунду"
        SpeechRecognizer.ERROR_SERVER -> "Сервер распознавания вернул ошибку"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не услышана"
        else -> "Ошибка распознавания ($error)"
    }
}
