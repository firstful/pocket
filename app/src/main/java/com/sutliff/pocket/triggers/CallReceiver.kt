package com.sutliff.pocket.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.sutliff.pocket.datastore.readConfig
import com.sutliff.pocket.runtime.HeadlessAgentRuntime
import com.sutliff.pocket.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {

    private val TAG = "CallReceiver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "unknown"

        if (state != TelephonyManager.EXTRA_STATE_RINGING) return
        Log.d(TAG, "Incoming call from $number")

        scope.launch {
            val rules = listOf(
                TriggerRule(
                    name = "incoming_call",
                    senderPattern = ".*",
                    bodyPattern = ".*",
                    promptTemplate = "Incoming phone call from {sender}. Decide whether to answer, decline, or take a message."
                )
            )
            for (rule in rules) {
                if (rule.matches(number, state)) {
                    Telemetry.log(context, "call_trigger_matched", mapOf("from" to number))
                    startAgentSession(context, rule, number, state)
                    break
                }
            }
        }
    }

    private fun startAgentSession(context: Context, rule: TriggerRule, number: String, state: String) {
        val prompt = rule.promptTemplate.replace("{sender}", number).replace("{body}", state)
        scope.launch {
            try {
                val runtime = HeadlessAgentRuntime(
                    inferenceConfig = readConfig(context),
                    workingDir = context.filesDir.absolutePath,
                    appContext = context
                )
                runtime.run(
                    sessionId = "call_${System.currentTimeMillis()}",
                    prompt = prompt,
                    onComplete = { result ->
                        Log.d(TAG, "Agent session completed: ${result.take(200)}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Agent session failed", e)
                Telemetry.logError(context, "CallReceiver", e)
            }
        }
    }
}
