package com.sutliff.pocket

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.content.Intent
import com.sutliff.pocket.bridge.AgentBridgeService
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import com.sutliff.pocket.datastore.getInferenceConfig
import com.sutliff.pocket.ui.AppNavigation
import com.sutliff.pocket.ui.theme.PocketTheme
import com.sutliff.pocket.telemetry.Telemetry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt-in permissions (todo #424): NO launch-time prompt. SMS/location/
        // notifications are disabled by default and granted only from
        // Settings > Permissions. Declared manifest permissions are NOT
        // requested here.
        Telemetry.log(this, "main_activity_create")
        setContent {
            val config by produceState(initialValue = com.sutliff.pocket.data.InferenceConfig()) {
                value = com.sutliff.pocket.datastore.getInferenceConfig(this@MainActivity).first()
            }
            val darkTheme = when (config.darkMode) {
                1 -> true
                2 -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            PocketTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentApp.foregroundActivity = java.lang.ref.WeakReference(this)
        Telemetry.log(this, "main_activity_resume")
        if (AgentApp.bridgeStartPending) {
            try {
                startService(Intent(this, AgentBridgeService::class.java))
                AgentApp.bridgeStartPending = false
                Telemetry.log(this, "bridge_service_start_retried")
            } catch (e: Exception) {
                Telemetry.logError(this, "bridge_start_retry_blocked", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (AgentApp.foregroundActivity?.get() === this) AgentApp.foregroundActivity = null
        Telemetry.log(this, "main_activity_pause")
    }
}