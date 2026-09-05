package com.arise.assistant.tools

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.arise.assistant.engine.ToolResult
import com.arise.assistant.util.AppResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * LEVEL 2 / LEVEL 3 — app integrations.
 *
 * WhatsApp execution order (per product spec): official API (none exists for
 * sending) → deep link pre-fill (wa.me) → AccessibilityService auto-send →
 * visual fallback. We only ever say “sent” when the on-screen send button was
 * actually pressed AND the composed message is gone from the input box.
 */
object MessagingExecutors {

    fun all(): List<ToolSpec> = listOf(
        ToolSpec("send_whatsapp_message", "Send a WhatsApp message to a contact",
            "contact: name or phone (required), message: text (required)") { a -> wa(a) },
        ToolSpec("send_sms", "Send an SMS text message",
            "contact: name or phone (required), message: text (required)") { a -> sms(a) },
        ToolSpec("call_contact", "Call a contact (opens the dialer for your confirmation)",
            "contact: name or phone (required)") { a -> call(a) },
        ToolSpec("send_email", "Open a compose email to a contact",
            "contact: name or email (required), message: text (required)") { a -> email(a) }
    )

    // ---------------- helpers ----------------

    private suspend fun AgentContext.resolveContact(tool: String, raw: String): Pair<String, String>? {
        val c = raw.trim().removeSuffix(".").removePrefix("to ").trim()
        if (c.isEmpty()) { progress("empty contact"); return null }
        // direct phone/email literal
        if (c.contains("@")) return "email" to c
        if (c.any { it.isDigit() } && c.replace(Regex("[^\\d]"), "").length >= 7) return "phone" to normalizePhone(c)
        // consult on-device contact cache
        val cache = ctx.getSharedPreferences("arise_contacts", Context.MODE_PRIVATE)
        cache.getString(c.lowercase(Locale.ROOT), null)?.let { return "cached" to it }
        // live contacts query (needs permission)
        if (hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
            queryContactNumber(ctx, c)?.let { num ->
                cache.edit().putString(c.lowercase(Locale.ROOT), num).apply()
                return "contacts" to num
            }
        }
        return null
    }

    private fun normalizePhone(p: String): String = p.replace(Regex("[^+\\d]"), "")

    private fun hasPermission(context: Context, perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun queryContactNumber(context: Context, display: String): String? = try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$display%"), null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                if (name.equals(display, true) || name.contains(display, true) || display.contains(name, true)) {
                    return@use normalizePhone(cur.getString(0) ?: continue)
                }
            }
            null
        }
    } catch (_: Exception) { null }

    private fun queryContactEmail(context: Context, display: String): String? = try {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$display%"), null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                if (name.equals(display, true) || name.contains(display, true)) return@use cur.getString(0)
            }
            null
        }
    } catch (_: Exception) { null }

    private fun hasWhatsApp(ctx: Context): Boolean = AppResolver.isPackageInstalled(ctx, "com.whatsapp")

    // ---------------- WhatsApp ----------------

    private suspend fun AgentContext.wa(args: Map<String, String>): ToolResult {
        args.req("send_whatsapp_message", "contact", "message")?.let { return it }
        if (!settings.allowMessagingActions) return blockedResult("send_whatsapp_message",
            "Messaging actions are disabled in Arise Settings")
        val name = args["contact"]!!
        val message = args["message"]!!
        if (!hasWhatsApp(ctx)) {
            return blockedResult("send_whatsapp_message", "WhatsApp is not installed on this phone")
        }
        val resolved = resolveContact("send_whatsapp_message", name)
        if (resolved == null) {
            if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
                return noPermResult("send_whatsapp_message",
                    "To reach $name I need Contacts permission — grant it in Arise permissions, then say it again")
            }
            return failResult("send_whatsapp_message", "I couldn’t find “$name” in your contacts")
        }
        val number = if (resolved.second.startsWith("+") || resolved.second.any { it.isDigit() }) resolved.second else return failResult("send_whatsapp_message", "No phone number for $name")

        progress("Opening WhatsApp for $name…")
        val url = "https://wa.me/${number.trimStart('+')}?text=" + Uri.encode(message)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val s = access
        if (s == null) {
            return partialResult("send_whatsapp_message",
                "WhatsApp is open with your message to $name ready — press the send arrow to deliver it")
        }
        delay(1800)
        // make sure the message text is actually in the input box
        var typed = false
        val nodesBefore = s.snapshotNodes()
        if (!nodesBefore.any { it.editable && (it.text ?: "").contains(message.take(8), ignoreCase = true) }) {
            val tr = s.typeText(message)
            if (tr.ok) typed = true
        } else typed = true
        if (!typed) return partialResult("send_whatsapp_message",
            "WhatsApp is open with $name — I couldn’t type reliably; please press send")

        delay(300)
        val press = s.clickByDescExact("Send")
            .let { if (it.ok) it else s.clickOnText("send") }
        if (!press.ok) return partialResult("send_whatsapp_message",
            "Message is typed for $name — press the send arrow to deliver")

        // verify: composed text should be gone from the input field after sending
        delay(1400)
        val after = s.snapshotNodes()
        val stillPresent = after.any { it.editable && (it.text ?: "").contains(message.take(10), ignoreCase = true) }
        return if (!stillPresent) {
            okResult("send_whatsapp_message", "Message sent to $name", output = "whatsapp:$name")
        } else {
            partialResult("send_whatsapp_message",
                "The send arrow was pressed for $name — please confirm the green tick in WhatsApp")
        }
    }

    // ---------------- SMS ----------------

    private suspend fun AgentContext.sms(args: Map<String, String>): ToolResult {
        args.req("send_sms", "contact", "message")?.let { return it }
        if (!settings.allowMessagingActions) return blockedResult("send_sms", "Messaging is disabled in Arise Settings")
        if (!hasPermission(ctx, Manifest.permission.SEND_SMS)) {
            return noPermResult("send_sms", "Sending SMS needs the “Send SMS” permission (Android also requires Arise to be your default SMS app)")
        }
        val resolved = resolveContact("send_sms", args["contact"]!!)
            ?: return if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
                noPermResult("send_sms", "I need Contacts permission to find ${args["contact"]}")
            } else failResult("send_sms", "Couldn’t find ${args["contact"]} in contacts")
        val number = resolved.second.takeIf { it.any(Char::isDigit) }
            ?: return failResult("send_sms", "No phone number for ${args["contact"]}")
        val message = args["message"]!!

        progress("Sending SMS…")
        val sent = CompletableDeferred<Int>()
        val action = "com.arise.assistant.SMS_SENT_${System.currentTimeMillis()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val code = resultCode
                if (sent.isActive) sent.complete(code)
            }
        }
        val filter = IntentFilter(action)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") ctx.registerReceiver(receiver, filter)
        }
        try {
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            SmsManager.getDefault().sendTextMessage(number, null, message, pi, null)
            val code = withTimeoutOrNull(8000) { sent.await() }
                ?: return partialResult("send_sms", "No delivery report within 8s — check the Messages app")
            return if (code == Activity.RESULT_OK) {
                okResult("send_sms", "SMS sent to ${args["contact"]}", output = "sms:$number")
            } else {
                val reason = when (code) {
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
                    else -> "code $code"
                }
                failResult("send_sms", "SMS failed: $reason")
            }
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    // ---------------- Call ----------------

    private suspend fun AgentContext.call(args: Map<String, String>): ToolResult {
        args.req("call_contact", "contact")?.let { return it }
        if (!settings.allowMessagingActions) return blockedResult("call_contact", "Call actions are disabled in Settings")
        val name = args["contact"]!!
        val resolved = resolveContact("call_contact", name)
            ?: return failResult("call_contact",
                if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) "I need Contacts permission to find $name"
                else "I couldn’t find $name in your contacts")
        val number = resolved.second.takeIf { it.any(Char::isDigit) }
            ?: return failResult("call_contact", "No phone number for $name")
        val uri = Uri.parse("tel:$number")
        // We deliberately open the dialer (ACTION_DIAL) for the user to press call —
        // Arise never places calls silently.
        ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return partialResult("call_contact", "Dialer opened for $name — press call when ready")
    }

    // ---------------- Email ----------------

    private suspend fun AgentContext.email(args: Map<String, String>): ToolResult {
        args.req("send_email", "contact", "message")?.let { return it }
        if (!settings.allowMessagingActions) return blockedResult("send_email", "Email actions are disabled in Settings")
        val name = args["contact"]!!
        var address = if (name.contains("@")) name else queryContactEmail(ctx, name)
        if (address == null && hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
            // accept gmail-inferred names as last resort? no — stay honest
        }
        if (address == null) {
            return failResult("send_email",
                if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) "I need Contacts permission to find an address for $name"
                else "I couldn’t find an email address for $name")
        }
        val body = args["message"]!!
        val mailto = Uri.parse("mailto:$address").buildUpon()
            .appendQueryParameter("subject", "Arise")
            .appendQueryParameter("body", body).build()
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, mailto).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return partialResult("send_email", "Compose opened for $name — press send in your mail app to finish")
    }
}
