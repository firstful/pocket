package com.sutliff.pocket

import android.app.Application
import android.content.Intent
import com.sutliff.pocket.bridge.AgentBridgeService

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start the bridge service on app launch
        startService(Intent(this, AgentBridgeService::class.java))
    }
}