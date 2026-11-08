package com.sutliff.pocket.triggers

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sutliff.pocket.datastore.readConfig
import com.sutliff.pocket.runtime.HeadlessAgentRuntime
import com.sutliff.pocket.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationTriggerService : NotificationListenerService() {

    private val TAG = "NotificationTriggerService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Opt-in gate (todo #424): notification triggers are DISABLED BY DEFAULT.
        // Both the DataStore flag AND the system Notification Listener grant are
        // required; Settings > Permissions explains the blast radius.
        val optIn = try {
            kotlinx.coroutines.runBlocking {
                com.sutliff.pocket.datastore.getPermissionOptIns(applicationContext)
            }
        } catch (e: Exception) {
            null
        }
        if (optIn?.notificationListenerEnabled != true ||
            !com.sutliff.pocket.permissions.NotificationOptIns.isListenerEnabled(applicationContext)
        ) {
            Log.d(TAG, "Notification received but notification opt-in off — ignoring")
            return
        }
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val fullText = "$title $text".trim()
        if (fullText.isBlank()) return

        // Skip our own notifications to avoid feedback loops
        if (pkg == packageName) return

        Log.d(TAG, "Notification from $pkg: $fullText")

        scope.launch {
            val rules = loadTriggerRules()
            for (rule in rules) {
                if (rule.matches(pkg, fullText)) {
                    Log.d(TAG, "Notification trigger matched: ${rule.name}")
                    Telemetry.log(applicationContext, "notification_trigger_matched", mapOf("rule" to rule.name, "pkg" to pkg))
                    startAgentSession(rule, pkg, fullText)
                    break
                }
            }
        }
    }

    private fun loadTriggerRules(): List<TriggerRule> {
        return listOf(
            TriggerRule(
                name = "keyword_agent",
                senderPattern = ".*",
                bodyPattern = ".*\\b(agent|run|exec|pocket)\\b.*",
                promptTemplate = "Notification from {sender}: {body}\n\nProcess this notification and take appropriate action."
            )
        )
    }

    private fun startAgentSession(rule: TriggerRule, pkg: String, text: String) {
        val prompt = rule.promptTemplate
            .replace("{sender}", pkg)
            .replace("{body}", text)

        scope.launch {
            try {
                val runtime = HeadlessAgentRuntime(
                    inferenceConfig = readConfig(applicationContext),
                    workingDir = applicationContext.filesDir.absolutePath,
                    appContext = applicationContext
                )
                runtime.run(
                    sessionId = "notif_${System.currentTimeMillis()}",
                    prompt = prompt,
                    onComplete = { result ->
                        Log.d(TAG, "Agent session completed: ${result.take(200)}")
                        // Post a notification with the result
                        try {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                            val channel = "agent_notifications"
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                nm.createNotificationChannel(
                                    android.app.NotificationChannel(channel, "Agent", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                                )
                            }
                            val notification = android.app.Notification.Builder(applicationContext, channel)
                                .setContentTitle("Pocket agent reply")
                                .setContentText(result.take(100))
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setAutoCancel(true)
                                .build()
                            nm.notify(System.currentTimeMillis().toInt(), notification)
                        } catch (ignored: Exception) { }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Agent session failed", e)
                Telemetry.logError(applicationContext, "NotificationTriggerService", e)
            }
        }
    }
}
