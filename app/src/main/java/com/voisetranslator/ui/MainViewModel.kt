package com.voisetranslator.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voisetranslator.VoiseApp
import com.voisetranslator.audio.AacEncoder
import com.voisetranslator.core.DeliveryMode
import com.voisetranslator.core.OutgoingFiles
import com.voisetranslator.core.Recipient
import com.voisetranslator.core.Settings
import com.voisetranslator.core.TranslationEngine
import com.voisetranslator.speech.SpeechEvent
import com.voisetranslator.translate.GeminiTranslator
import com.voisetranslator.translate.MlKitTranslator
import com.voisetranslator.translate.Translator
import com.voisetranslator.tts.VoiceOption
import com.voisetranslator.whatsapp.AutoSendCoordinator
import com.voisetranslator.whatsapp.WhatsAppSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where the pipeline currently is. Drives the big button and the progress copy. */
enum class Stage {
    IDLE,
    LISTENING,
    TRANSLATING,
    SYNTHESIZING,
    READY,
    SENDING,
}

data class UiState(
    val settings: Settings = Settings(),
    val stage: Stage = Stage.IDLE,
    val russianText: String = "",
    val partialText: String = "",
    val englishText: String = "",
    val micLevel: Float = 0f,
    val audio: File? = null,
    val error: String? = null,
    val notice: String? = null,
    val voices: List<VoiceOption> = emptyList(),
    val whatsAppInstalled: Boolean = true,
    val speechAvailable: Boolean = true,
) {
    val isBusy: Boolean
        get() = stage == Stage.TRANSLATING || stage == Stage.SYNTHESIZING || stage == Stage.SENDING

    /** Text shown in the Russian box: live partials while listening, the final text after. */
    val displayedRussian: String
        get() = if (stage == Stage.LISTENING && partialText.isNotBlank()) partialText else russianText
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<VoiseApp>()
    private val prefs get() = app.prefs

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Intents the Activity must start on our behalf. */
    private val _launches = Channel<Intent>(Channel.BUFFERED)
    val launches = _launches.receiveAsFlow()

    private var dictationJob: Job? = null
    private var pipelineJob: Job? = null
    private var mlKit: MlKitTranslator? = null

    init {
        viewModelScope.launch {
            prefs.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        _state.update {
            it.copy(
                whatsAppInstalled = WhatsAppSender.installedPackage(app) != null,
                speechAvailable = app.speech.isAvailable(),
            )
        }
        loadVoices()
    }

    // ---- dictation -------------------------------------------------------------------

    fun toggleDictation() {
        if (_state.value.stage == Stage.LISTENING) stopDictation() else startDictation()
    }

    fun startDictation() {
        if (_state.value.isBusy) return
        dictationJob?.cancel()
        pipelineJob?.cancel()

        _state.update {
            it.copy(
                stage = Stage.LISTENING,
                russianText = "",
                partialText = "",
                englishText = "",
                audio = null,
                error = null,
                notice = null,
                micLevel = 0f,
            )
        }

        dictationJob = viewModelScope.launch {
            app.speech.listen().collect { event ->
                when (event) {
                    is SpeechEvent.ReadyForSpeech -> Unit
                    is SpeechEvent.Level -> _state.update { it.copy(micLevel = event.rms) }
                    is SpeechEvent.Partial -> _state.update { it.copy(partialText = event.text) }
                    is SpeechEvent.Final -> {
                        _state.update {
                            it.copy(russianText = event.text, partialText = "", micLevel = 0f)
                        }
                        runPipeline(event.text)
                    }

                    is SpeechEvent.Failed -> _state.update {
                        it.copy(stage = Stage.IDLE, error = event.message, micLevel = 0f)
                    }
                }
            }
        }
    }

    fun stopDictation() {
        dictationJob?.cancel()
        dictationJob = null
        _state.update {
            // Keep whatever the recogniser had heard so far so the text is not lost.
            val heard = it.partialText.ifBlank { it.russianText }
            it.copy(stage = Stage.IDLE, russianText = heard, partialText = "", micLevel = 0f)
        }
    }

    // ---- translate + synthesise ------------------------------------------------------

    /** Re-runs translation and synthesis for text the user edited by hand. */
    fun retranslate(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(russianText = text) }
        runPipeline(text)
    }

    private fun runPipeline(russian: String) {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                _state.update { it.copy(stage = Stage.TRANSLATING, error = null) }
                val settings = _state.value.settings
                val english = translator(settings).translate(russian).trim()
                if (english.isBlank()) {
                    _state.update { it.copy(stage = Stage.IDLE, error = "Перевод пустой") }
                    return@launch
                }
                _state.update { it.copy(englishText = english) }

                if (settings.deliveryMode == DeliveryMode.TEXT) {
                    _state.update { it.copy(stage = Stage.READY, audio = null) }
                } else {
                    _state.update { it.copy(stage = Stage.SYNTHESIZING) }
                    val audio = synthesize(english, settings)
                    _state.update { it.copy(stage = Stage.READY, audio = audio) }
                }

                if (settings.autoSend) send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(stage = Stage.IDLE, error = e.message ?: "Не удалось подготовить сообщение") }
            }
        }
    }

    private suspend fun synthesize(english: String, settings: Settings): File {
        OutgoingFiles.prune(app)
        val wav = OutgoingFiles.newFile(app, "wav")
        try {
            app.tts.synthesizeToWav(
                text = english,
                target = wav,
                voiceName = settings.voiceName,
                rate = settings.speechRate,
                pitch = settings.pitch,
            )
            val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
            return AacEncoder.wavToM4a(wav, m4a)
        } finally {
            // The intermediate WAV is never shared, so it can go immediately.
            wav.delete()
        }
    }

    private fun translator(settings: Settings): Translator = when (settings.engine) {
        TranslationEngine.GEMINI -> GeminiTranslator(settings.geminiApiKey, settings.geminiModel)
        TranslationEngine.OFFLINE -> mlKit ?: MlKitTranslator().also { mlKit = it }
    }

    /** Downloads the on-device model ahead of time, from the settings screen. */
    fun downloadOfflineModel() {
        viewModelScope.launch {
            _state.update { it.copy(notice = "Скачиваю офлайн-модель…", error = null) }
            try {
                (mlKit ?: MlKitTranslator().also { mlKit = it }).ensureModel()
                _state.update { it.copy(notice = "Офлайн-модель готова") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = null, error = e.message) }
            }
        }
    }

    // ---- delivery --------------------------------------------------------------------

    fun send() {
        val current = _state.value
        val settings = current.settings
        val recipient: Recipient? = settings.selectedRecipient

        viewModelScope.launch {
            try {
                _state.update { it.copy(stage = Stage.SENDING, error = null) }

                if (settings.autoSend && AutoSendCoordinator.isServiceEnabled(app)) {
                    AutoSendCoordinator.arm(recipient?.name.orEmpty())
                } else {
                    AutoSendCoordinator.disarm()
                }

                val intent = when (settings.deliveryMode) {
                    DeliveryMode.TEXT ->
                        WhatsAppSender.textIntent(app, recipient, current.englishText)

                    DeliveryMode.AUDIO -> {
                        val audio = current.audio
                            ?: throw IllegalStateException("Аудио ещё не готово")
                        WhatsAppSender.audioIntent(app, recipient, audio)
                    }
                }

                _launches.send(intent)
                _state.update {
                    it.copy(
                        stage = Stage.READY,
                        notice = if (settings.autoSend) "Отправляю в WhatsApp…" else "Открываю WhatsApp",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AutoSendCoordinator.disarm()
                _state.update { it.copy(stage = Stage.READY, error = e.message ?: "Не удалось открыть WhatsApp") }
            }
        }
    }

    // ---- settings passthrough ---------------------------------------------------------

    fun loadVoices() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(voices = app.tts.englishVoices()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun previewVoice(voiceName: String) {
        viewModelScope.launch {
            val settings = _state.value.settings
            val sample = _state.value.englishText.ifBlank { PREVIEW_SAMPLE }
            try {
                app.tts.preview(sample, voiceName, settings.speechRate, settings.pitch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun setEngine(value: TranslationEngine) = edit { prefs.setEngine(value) }
    fun setGeminiKey(value: String) = edit { prefs.setGeminiApiKey(value) }
    fun setGeminiModel(value: String) = edit { prefs.setGeminiModel(value) }
    fun setDeliveryMode(value: DeliveryMode) = edit { prefs.setDeliveryMode(value) }
    fun setVoice(value: String) = edit { prefs.setVoice(value) }
    fun setSpeechRate(value: Float) = edit { prefs.setSpeechRate(value) }
    fun setPitch(value: Float) = edit { prefs.setPitch(value) }
    fun setAutoSend(value: Boolean) = edit { prefs.setAutoSend(value) }
    fun selectRecipient(id: String) = edit { prefs.selectRecipient(id) }
    fun addRecipient(name: String, phone: String) = edit { prefs.addRecipient(name, phone) }
    fun removeRecipient(id: String) = edit { prefs.removeRecipient(id) }

    /** Lets the user repair a misheard word before it is translated. */
    fun editRussian(text: String) {
        _state.update { it.copy(russianText = text) }
    }

    fun editEnglish(text: String) {
        // Editing the translation invalidates the audio rendered from the previous wording.
        _state.update { it.copy(englishText = text, audio = null) }
    }

    /** Renders the current English text again after it was edited by hand. */
    fun resynthesize() {
        val settings = _state.value.settings
        val english = _state.value.englishText
        if (english.isBlank() || settings.deliveryMode == DeliveryMode.TEXT) return

        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                _state.update { it.copy(stage = Stage.SYNTHESIZING, error = null) }
                val audio = synthesize(english, settings)
                _state.update { it.copy(stage = Stage.READY, audio = audio) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(stage = Stage.READY, error = e.message) }
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    fun refreshEnvironment() {
        _state.update {
            it.copy(
                whatsAppInstalled = WhatsAppSender.installedPackage(app) != null,
                speechAvailable = app.speech.isAvailable(),
            )
        }
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    override fun onCleared() {
        dictationJob?.cancel()
        pipelineJob?.cancel()
        mlKit?.close()
        app.tts.stopPreview()
        super.onCleared()
    }

    private companion object {
        const val PREVIEW_SAMPLE = "Hi! This is how your voice message will sound in English."
    }
}
