package com.voisetranslator.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voise_settings")

/** Every persisted setting, exposed as one [Settings] stream. */
class Prefs(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            engine = p[Keys.ENGINE].toEnum(TranslationEngine.OFFLINE),
            geminiApiKey = p[Keys.GEMINI_KEY].orEmpty(),
            geminiModel = p[Keys.GEMINI_MODEL] ?: Settings.DEFAULT_GEMINI_MODEL,
            deliveryMode = p[Keys.DELIVERY].toEnum(DeliveryMode.AUDIO),
            voiceName = p[Keys.VOICE].orEmpty(),
            speechRate = p[Keys.RATE] ?: 1.0f,
            pitch = p[Keys.PITCH] ?: 1.0f,
            autoSend = p[Keys.AUTO_SEND] ?: false,
            lastRecipientId = p[Keys.LAST_RECIPIENT].orEmpty(),
            recipients = decodeRecipients(p[Keys.RECIPIENTS]),
        )
    }

    suspend fun setEngine(value: TranslationEngine) = put(Keys.ENGINE, value.name)

    suspend fun setGeminiApiKey(value: String) = put(Keys.GEMINI_KEY, value.trim())

    suspend fun setGeminiModel(value: String) = put(Keys.GEMINI_MODEL, value.trim())

    suspend fun setDeliveryMode(value: DeliveryMode) = put(Keys.DELIVERY, value.name)

    suspend fun setVoice(voiceName: String) = put(Keys.VOICE, voiceName)

    suspend fun setSpeechRate(value: Float) = put(Keys.RATE, value)

    suspend fun setPitch(value: Float) = put(Keys.PITCH, value)

    suspend fun setAutoSend(value: Boolean) = put(Keys.AUTO_SEND, value)

    suspend fun selectRecipient(id: String) = put(Keys.LAST_RECIPIENT, id)

    suspend fun addRecipient(name: String, phone: String) {
        val recipient = Recipient(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            phone = Recipient.normalizePhone(phone),
        )
        context.dataStore.edit { p ->
            val updated = decodeRecipients(p[Keys.RECIPIENTS]) + recipient
            p[Keys.RECIPIENTS] = json.encodeToString(updated)
            // First contact added becomes the default target.
            if (p[Keys.LAST_RECIPIENT].isNullOrEmpty()) p[Keys.LAST_RECIPIENT] = recipient.id
        }
    }

    suspend fun removeRecipient(id: String) {
        context.dataStore.edit { p ->
            val updated = decodeRecipients(p[Keys.RECIPIENTS]).filterNot { it.id == id }
            p[Keys.RECIPIENTS] = json.encodeToString(updated)
            if (p[Keys.LAST_RECIPIENT] == id) {
                p[Keys.LAST_RECIPIENT] = updated.firstOrNull()?.id.orEmpty()
            }
        }
    }

    private fun decodeRecipients(raw: String?): List<Recipient> {
        if (raw.isNullOrBlank()) return emptyList()
        // A malformed blob should not brick the app; start over with an empty list.
        return runCatching { json.decodeFromString<List<Recipient>>(raw) }.getOrDefault(emptyList())
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(fallback: E): E =
        this?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback

    private object Keys {
        val ENGINE = stringPreferencesKey("engine")
        val GEMINI_KEY = stringPreferencesKey("gemini_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val DELIVERY = stringPreferencesKey("delivery_mode")
        val VOICE = stringPreferencesKey("voice_name")
        val RATE = floatPreferencesKey("speech_rate")
        val PITCH = floatPreferencesKey("pitch")
        val AUTO_SEND = booleanPreferencesKey("auto_send")
        val LAST_RECIPIENT = stringPreferencesKey("last_recipient")
        val RECIPIENTS = stringPreferencesKey("recipients")
    }
}
