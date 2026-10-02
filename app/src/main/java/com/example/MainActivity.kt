package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.ui.CrashScreen
import com.example.util.CrashReporter
import com.example.ui.MainScreen
import com.example.ui.theme.CodeForgeTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CodeForgeApp

        // Android 13+: needed to show the "agent is working" notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val lastCrash = CrashReporter.readLastCrash(this)
        setContent {
            CodeForgeTheme(darkTheme = true) {
                var crashText by remember { mutableStateOf(lastCrash) }
                val crash = crashText
                if (crash != null) {
                    CrashScreen(
                        crashText = crash,
                        onContinue = {
                            CrashReporter.clear(this@MainActivity)
                            crashText = null
                        }
                    )
                } else {
                    MainScreen(
                        app = app,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
