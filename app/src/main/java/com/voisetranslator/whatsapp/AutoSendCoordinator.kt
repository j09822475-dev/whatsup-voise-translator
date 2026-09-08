package com.voisetranslator.whatsapp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * The hand-off between the app and [WhatsAppAutoSendService].
 *
 * An accessibility service is instantiated by the system, so it cannot be called directly.
 * Instead the app *arms* a short-lived request right before launching WhatsApp; the service
 * only acts while a request is armed, which keeps it from pressing buttons in WhatsApp at any
 * other time.
 */
object AutoSendCoordinator {

    data class Request(
        val recipientName: String,
        val expiresAt: Long,
    ) {
        val isAlive: Boolean get() = System.currentTimeMillis() < expiresAt
    }

    /** How long the service is allowed to act after the app launches WhatsApp. */
    private const val TTL_MS = 20_000L

    @Volatile
    private var pending: Request? = null

    fun arm(recipientName: String) {
        pending = Request(recipientName, System.currentTimeMillis() + TTL_MS)
    }

    /** The live request, or null when nothing is armed or the window has passed. */
    fun current(): Request? {
        val request = pending ?: return null
        if (!request.isAlive) {
            pending = null
            return null
        }
        return request
    }

    fun disarm() {
        pending = null
    }

    /** Whether the user has switched our service on in Android's accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val component = ComponentName(context, WhatsAppAutoSendService::class.java)
        // Android stores the list in either the long or the short flattened form.
        val expected = setOf(component.flattenToString(), component.flattenToShortString())
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { entry -> expected.any { it.equals(entry, ignoreCase = true) } }
    }
}
