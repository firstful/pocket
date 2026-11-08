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

import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* grants handled by tools via Settings if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestDangerousPermissions()
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

    private fun requestDangerousPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
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
        Telemetry.log(this, "main_activity_pause")
    }
}