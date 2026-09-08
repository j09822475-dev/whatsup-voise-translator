package com.voisetranslator

import android.app.Application
import com.voisetranslator.core.Prefs
import com.voisetranslator.speech.SpeechRecognizerController
import com.voisetranslator.tts.TtsEngine

/**
 * Holds the few long-lived objects the app needs. Small enough that a DI framework would
 * cost more than it saves.
 */
class VoiseApp : Application() {

    val prefs: Prefs by lazy { Prefs(this) }
    val tts: TtsEngine by lazy { TtsEngine(this) }
    val speech: SpeechRecognizerController by lazy { SpeechRecognizerController(this) }

    override fun onTerminate() {
        tts.shutdown()
        super.onTerminate()
    }
}
