package com.voisetranslator.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device translation. The ru→en model (~30 MB) is downloaded once on first use and then
 * works with no network and no API key.
 */
class MlKitTranslator : Translator {

    private val delegate = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.RUSSIAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    private val downloadLock = Mutex()
    @Volatile
    private var modelReady = false

    /** Downloads the language pack if it is not on the device yet. */
    suspend fun ensureModel(wifiOnly: Boolean = false) {
        if (modelReady) return
        withContext(Dispatchers.IO) {
            downloadLock.withLock {
                if (!modelReady) {
                    val conditions = DownloadConditions.Builder()
                        .apply { if (wifiOnly) requireWifi() }
                        .build()
                    try {
                        delegate.downloadModelIfNeeded(conditions).await()
                        modelReady = true
                    } catch (e: Exception) {
                        throw TranslationException(
                            "Не удалось скачать офлайн-модель перевода. Проверьте интернет — она нужна только один раз.",
                            e,
                        )
                    }
                }
            }
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        ensureModel()
        try {
            delegate.translate(text).await()
        } catch (e: Exception) {
            throw TranslationException("Офлайн-перевод не удался: ${e.message}", e)
        }
    }

    override fun close() {
        delegate.close()
    }
}
