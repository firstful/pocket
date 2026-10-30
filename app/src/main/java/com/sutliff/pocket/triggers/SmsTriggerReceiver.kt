package com.sutliff.pocket.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.sutliff.pocket.datastore.readConfig
import com.sutliff.pocket.runtime.HeadlessAgentRuntime
import com.sutliff.pocket.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsTriggerReceiver : BroadcastReceiver() {

    private val TAG = "SmsTriggerReceiver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val fullBody = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress ?: "unknown"

        Log.d(TAG, "SMS from $sender: $fullBody")

        // Load trigger rules from DataStore
        scope.launch {
            val config = readConfig(context)
            val rules = loadTriggerRules(context)

            for (rule in rules) {
                if (rule.matches(sender, fullBody)) {
                    Log.d(TAG, "Trigger matched: ${rule.name}, starting agent session")
                    Telemetry.log(context, "sms_trigger_matched", mapOf("rule" to rule.name))
                    startAgentSession(context, rule, sender, fullBody)
                    break
                }
            }
        }
    }

    private fun loadTriggerRules(context: Context): List<TriggerRule> {
        // TODO: Load from DataStore/SharedPreferences
        // Default: match any SMS containing "agent" or "run"
        return listOf(
            TriggerRule(
                name = "keyword_agent",
                senderPattern = ".*",
                bodyPattern = ".*(agent|run|exec).*",
                promptTemplate = "SMS from {sender}: {body}\n\nProcess this message and take appropriate action."
            )
        )
    }

    private fun startAgentSession(context: Context, rule: TriggerRule, sender: String, body: String) {
        val prompt = rule.promptTemplate
            .replace("{sender}", sender)
            .replace("{body}", body)

        scope.launch {
            try {
                val runtime = HeadlessAgentRuntime(
                    inferenceConfig = readConfig(context),
                    workingDir = context.filesDir.absolutePath,
                    appContext = context
                )
                runtime.run(
                    sessionId = "sms_${System.currentTimeMillis()}",
                    prompt = prompt,
                    onComplete = { result ->
                        Log.d(TAG, "Agent session completed: ${result.take(200)}")
                        // Send SMS reply with the result (truncated to SMS limits)
                        try {
                            val reply = result.trim().take(1400) // multi-part safe
                            val sms = android.telephony.SmsManager.getDefault()
                            if (reply.length > 160) {
                                val parts = sms.divideMessage(reply)
                                sms.sendMultipartTextMessage(sender, null, parts, null, null)
                            } else {
                                sms.sendTextMessage(sender, null, reply, null, null)
                            }
                            Log.d(TAG, "SMS reply sent to $sender")
                            Telemetry.log(context, "sms_reply_sent", mapOf("to" to sender))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send SMS reply", e)
                            // Fall back to a notification
                            try {
                                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                val notification = android.app.Notification.Builder(context, "agent_notifications")
                                    .setContentTitle("Pocket agent reply")
                                    .setContentText(result.take(100))
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setAutoCancel(true)
                                    .build()
                                nm.notify(System.currentTimeMillis().toInt(), notification)
                            } catch (ignored: Exception) { }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Agent session failed", e)
                Telemetry.logError(context, "SmsTriggerReceiver", e)
            }
        }
    }
}

data class TriggerRule(
    val name: String,
    val senderPattern: String,
    val bodyPattern: String,
    val promptTemplate: String
) {
    fun matches(sender: String, body: String): Boolean {
        return sender.matches(Regex(senderPattern, RegexOption.IGNORE_CASE)) &&
               body.matches(Regex(bodyPattern, RegexOption.IGNORE_CASE))
    }
}