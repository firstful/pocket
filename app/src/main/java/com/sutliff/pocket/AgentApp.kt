package com.sutliff.pocket

import android.app.Application
import android.content.Intent
import com.sutliff.pocket.bridge.AgentBridgeService
import com.sutliff.pocket.telemetry.Telemetry

class AgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Telemetry.log(this, "app_create")
        // Start the bridge service on app launch. On Android 12+ this can be
        // rejected when the process is restored in the background (install /
        // update restore) — BackgroundServiceStartNotAllowedException. Catch it
        // and let MainActivity retry once the app is actually foreground.
        bridgeStartPending = try {
            startService(Intent(this, AgentBridgeService::class.java))
            false
        } catch (e: Exception) {
            Telemetry.logError(this, "bridge_start_blocked", e)
            true
        }
    }

    companion object {
        /** Set when the initial service start was rejected; retried from MainActivity.onResume. */
        @Volatile
        var bridgeStartPending: Boolean = false
    }
}
