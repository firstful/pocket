package com.sutliff.pocket

import android.app.Application
import android.content.Intent
import com.sutliff.pocket.bridge.AgentBridgeService
import com.sutliff.pocket.telemetry.Telemetry

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.log(this, "app_create")
        // Start the bridge service on app launch
        startService(Intent(this, AgentBridgeService::class.java))
    }
}