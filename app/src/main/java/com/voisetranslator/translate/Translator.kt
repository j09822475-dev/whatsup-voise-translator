package com.voisetranslator.translate

/** Russian in, English out. Implementations are single-direction on purpose. */
interface Translator {
    suspend fun translate(text: String): String

    /** Releases any native/on-device resources. Safe to call more than once. */
    fun close() = Unit
}

/** Thrown when a backend could not produce a translation; the message is user-facing. */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
