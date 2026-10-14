package com.sutliff.pocket

import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sutliff.pocket.ui.AppNavigation
import com.sutliff.pocket.ui.theme.PocketTheme
import com.sutliff.pocket.telemetry.Telemetry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetry.log(this, "main_activity_create")
        setContent {
            PocketTheme {
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
        Telemetry.log(this, "main_activity_resume")
    }

    override fun onPause() {
        super.onPause()
        Telemetry.log(this, "main_activity_pause")
    }
}