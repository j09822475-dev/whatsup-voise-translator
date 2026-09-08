package com.voisetranslator.whatsapp

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses the last button for you.
 *
 * After the app hands an audio attachment to WhatsApp, the message still sits on a preview
 * screen waiting for a tap on Send — and, if WhatsApp ignored the `jid` extra, on the contact
 * picker before that. This service finishes those two steps, but only inside the short window
 * [AutoSendCoordinator] keeps open, and only in WhatsApp (the manifest restricts it to the two
 * WhatsApp packages).
 */
class WhatsAppAutoSendService : AccessibilityService() {

    /** Set once we click Send, so a burst of follow-up events cannot double-send. */
    private var sendClicked = false

    /** Guards against re-picking a contact while the picker is still settling. */
    private var contactClickedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = AutoSendCoordinator.current()
        if (request == null) {
            sendClicked = false
            contactClickedAt = 0L
            return
        }
        if (sendClicked) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != WhatsAppSender.PACKAGE_CONSUMER && pkg != WhatsAppSender.PACKAGE_BUSINESS) return

        // Send first: on the direct-to-chat path the picker never appears.
        if (clickSend(root, pkg)) {
            sendClicked = true
            AutoSendCoordinator.disarm()
            return
        }

        if (request.recipientName.isNotBlank()) {
            val settled = System.currentTimeMillis() - contactClickedAt > CONTACT_COOLDOWN_MS
            if (settled && clickContact(root, request.recipientName)) {
                contactClickedAt = System.currentTimeMillis()
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun clickSend(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val byId = SEND_IDS.asSequence()
            .flatMap { root.findAccessibilityNodeInfosByViewId("$pkg:id/$it").orEmpty().asSequence() }
            .firstOrNull { it.isVisibleToUser }
        if (byId != null && performClick(byId)) return true

        // WhatsApp renames view ids between releases; the content description is more stable.
        return findByDescription(root, SEND_DESCRIPTIONS)?.let { performClick(it) } == true
    }

    private fun clickContact(root: AccessibilityNodeInfo, name: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(name).orEmpty()
        val node = matches.firstOrNull {
            it.isVisibleToUser && it.text?.toString().equals(name, ignoreCase = true)
        } ?: matches.firstOrNull { it.isVisibleToUser }

        return node != null && performClick(node)
    }

    /** Clicks [node], or the nearest ancestor that actually handles clicks. */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        var depth = 0
        while (candidate != null && depth < MAX_ANCESTOR_DEPTH) {
            if (candidate.isClickable && candidate.isEnabled) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            candidate = candidate.parent
            depth++
        }
        Log.d(TAG, "No clickable ancestor for ${node.viewIdResourceName}")
        return false
    }

    private fun findByDescription(
        root: AccessibilityNodeInfo,
        descriptions: Set<String>,
    ): AccessibilityNodeInfo? {
        if (!root.isVisibleToUser) return null

        val description = root.contentDescription?.toString()?.trim()
        if (description != null && descriptions.any { it.equals(description, ignoreCase = true) }) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findByDescription(child, descriptions)?.let { return it }
        }
        return null
    }

    private companion object {
        const val TAG = "VoiseAutoSend"
        const val CONTACT_COOLDOWN_MS = 1_500L
        const val MAX_ANCESTOR_DEPTH = 6

        /** Send button ids seen across recent WhatsApp releases. */
        val SEND_IDS = listOf("send", "send_btn", "media_send")

        /** Localised labels WhatsApp puts on the send button. */
        val SEND_DESCRIPTIONS = setOf("Send", "Отправить", "Enviar", "Senden")
    }
}
