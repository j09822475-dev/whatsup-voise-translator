package com.voisetranslator.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.voisetranslator.core.Recipient
import java.io.File
import java.net.URLEncoder

class WhatsAppNotInstalledException :
    Exception("WhatsApp не найден на устройстве")

/** Builds and fires the intents that hand a finished message over to WhatsApp. */
object WhatsAppSender {

    const val PACKAGE_CONSUMER = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"

    /** Mime type of the files [com.voisetranslator.audio.AacEncoder] produces. */
    const val AUDIO_MIME = "audio/mp4"

    fun installedPackage(context: Context): String? =
        listOf(PACKAGE_CONSUMER, PACKAGE_BUSINESS).firstOrNull { context.isInstalled(it) }

    /**
     * Opens the recipient's chat with [text] already typed in.
     *
     * `wa.me` is WhatsApp's own documented deep link, so this path keeps working across
     * WhatsApp updates.
     */
    fun textIntent(context: Context, recipient: Recipient?, text: String): Intent {
        val pkg = installedPackage(context) ?: throw WhatsAppNotInstalledException()
        val encoded = URLEncoder.encode(text, "UTF-8")
        val phone = recipient?.phone.orEmpty()
        val url = if (phone.isNotBlank()) "https://wa.me/$phone?text=$encoded" else "https://wa.me/?text=$encoded"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Hands the synthesised audio to WhatsApp as an attachment.
     *
     * When the recipient has a phone number, the undocumented `jid` extra makes WhatsApp open
     * that chat directly instead of its contact picker. WhatsApp may drop that extra in a
     * future release; the picker then shows up and the user (or the accessibility service)
     * chooses the chat, so the failure mode is one extra tap rather than a crash.
     */
    fun audioIntent(context: Context, recipient: Recipient?, audio: File): Intent {
        val pkg = installedPackage(context) ?: throw WhatsAppNotInstalledException()
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            audio,
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = AUDIO_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            recipient?.phone?.takeIf { it.isNotBlank() }?.let { putExtra("jid", "$it@s.whatsapp.net") }
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun Context.isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
