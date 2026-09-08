package com.voisetranslator.core

import kotlinx.serialization.Serializable

/** Which backend turns Russian into English. */
enum class TranslationEngine {
    /** ML Kit on-device model. Free, works offline, no API key. */
    OFFLINE,

    /** Gemini REST API. Better with idioms and slang, needs a key and a connection. */
    GEMINI,
}

/** What actually lands in the WhatsApp chat. */
enum class DeliveryMode {
    /** Synthesised English speech, shared as an .m4a audio attachment. */
    AUDIO,

    /** Plain English text, prefilled in the chat via a wa.me link. */
    TEXT,
}

/**
 * A saved WhatsApp chat.
 *
 * [phone] is stored as bare digits in international format (no `+`, spaces or dashes)
 * because that is the shape `wa.me` links expect.
 */
@Serializable
data class Recipient(
    val id: String,
    val name: String,
    val phone: String,
) {
    val hasPhone: Boolean get() = phone.isNotBlank()

    companion object {
        /** Strips everything a person might type around a number: `+7 (900) 123-45-67`. */
        fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }
    }
}

data class Settings(
    val engine: TranslationEngine = TranslationEngine.OFFLINE,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val deliveryMode: DeliveryMode = DeliveryMode.AUDIO,
    /** `TextToSpeech.Voice.getName()`; empty means "whatever the engine picks for en". */
    val voiceName: String = "",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Let the accessibility service pick the chat and press Send by itself. */
    val autoSend: Boolean = false,
    val lastRecipientId: String = "",
    val recipients: List<Recipient> = emptyList(),
) {
    val selectedRecipient: Recipient?
        get() = recipients.firstOrNull { it.id == lastRecipientId } ?: recipients.firstOrNull()

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
    }
}
