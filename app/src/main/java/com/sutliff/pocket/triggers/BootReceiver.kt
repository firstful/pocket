package com.sutliff.pocket.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sutliff.pocket.bridge.AgentBridgeService

class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, starting AgentBridgeService")
            context.startService(Intent(context, AgentBridgeService::class.java))
        }
    }
}