package com.aurum.intelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.intelligence.ui.AurumApp
import com.aurum.intelligence.ui.theme.AurumTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val aurum = application as AurumApplication
            val startup by aurum.startupState.collectAsState()
            when (val state = startup) {
                StartupState.Starting -> StartupMessage("Starting Aurum")
                is StartupState.Failed -> StartupFailure(state.message, aurum::retryInitialization)
                StartupState.Ready, is StartupState.Degraded -> {
                    val settings by aurum.settingsRepository.settings.collectAsState(
                        initial = com.aurum.intelligence.data.AppSettings(),
                    )
                    AurumTheme(themeChoice = settings.theme) {
                        AurumApp(
                            startupWarning = (state as? StartupState.Degraded)?.message,
                            onRetryStartup = aurum::retryInitialization,
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StartupMessage(message: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(top = 16.dp))
    }
}

@androidx.compose.runtime.Composable
private fun StartupFailure(message: String, onRetry: () -> Unit) {
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Aurum could not start", style = MaterialTheme.typography.headlineSmall)
            Text(message, modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Retry") }
            Text("Your stored data has not been deleted.", modifier = Modifier.padding(top = 12.dp))
        }
    }
}