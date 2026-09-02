package com.aurum.intelligence.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.intelligence.data.AppSettings
import com.aurum.intelligence.data.ThemeChoice
import androidx.core.content.ContextCompat

private val refreshIntervals = listOf(15, 30, 60, 120, 240)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    archiveOperation: ArchiveOperationState,
    model: AurumViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) model.setBackgroundRefreshEnabled(true) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { model.exportArchive(contentResolver, it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importArchive(contentResolver, it) }
    }
    var pincode by rememberSaveable(settings.pincode) { mutableStateOf(settings.pincode) }
    var address by rememberSaveable(settings.preciseAddress) { mutableStateOf(settings.preciseAddress) }
    var locationSaveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard("APPEARANCE", "Theme") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeChoice.entries.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = settings.theme == choice,
                                onClick = { model.setTheme(choice) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
                            ) {
                                Text(choice.name)
                            }
                        }
                    }
                }
            }
            item {
                SettingsCard("LOCATION", "Delivery context") {
                    OutlinedTextField(
                        value = pincode,
                        onValueChange = { pincode = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pincode") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Precise address") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            locationSaveMessage = null
                            model.setLocation(pincode, address) { failure ->
                                locationSaveMessage = failure ?: "Location saved"
                            }
                        },
                        enabled = pincode.length == 6,
                    ) { Text("Save location") }
                    locationSaveMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(top = 8.dp),
                            color = if (message == "Location saved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                SettingsCard("REFRESH STARTUP", "Automatic collection") {
                    BrowserSettingRow(
                        title = "Refresh bullion on start",
                        detail = "Start direct bullion sources and the rendered Tanishq collection when Aurum opens.",
                        checked = settings.refreshBullionOnStart,
                        onCheckedChange = model::setRefreshBullionOnStart,
                    )
                    Spacer(Modifier.height(14.dp))
                    BrowserSettingRow(
                        title = "Refresh products on start",
                        detail = "Start retailer collection when Aurum opens. Progress stays in the Browser tab.",
                        checked = settings.refreshProductsOnStart,
                        onCheckedChange = model::setRefreshProductsOnStart,
                    )
                }
            }
            item {
                SettingsCard("REFRESH", "Background refresh") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Remind me to refresh", modifier = Modifier.weight(1f))
                        Switch(
                            modifier = Modifier.semantics { contentDescription = "Remind me to refresh" },
                            checked = settings.backgroundRefreshEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled || Build.VERSION.SDK_INT < 33 ||
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    model.setBackgroundRefreshEnabled(enabled)
                                } else {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                    if (settings.backgroundRefreshEnabled && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        Text(
                            "Notifications are disabled, so Aurum cannot alert you when a refresh reminder is due.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Interval", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val currentIndex = refreshIntervals.indexOfFirst { it >= settings.refreshIntervalMinutes }
                                .takeIf { it >= 0 } ?: refreshIntervals.lastIndex
                            IconButton(
                                enabled = currentIndex > 0,
                                modifier = Modifier.semantics { contentDescription = "Decrease refresh interval" },
                                onClick = { model.setRefreshIntervalMinutes(refreshIntervals[currentIndex - 1]) },
                            ) { Text("-") }
                            Text("${refreshIntervals[currentIndex]} min", fontWeight = FontWeight.Bold)
                            IconButton(
                                enabled = currentIndex < refreshIntervals.lastIndex,
                                onClick = { model.setRefreshIntervalMinutes(refreshIntervals[currentIndex + 1]) },
                            ) { Icon(Icons.Outlined.Add, contentDescription = "Increase refresh interval") }
                        }
                    }
                    Text(
                        "Best effort: Aurum records the request and notifies you to open the in-app browser collection.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                SettingsCard("DATA", "Backup and restore") {
                    Text(
                        "Export or import products, price history, and raw bridge payloads.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = archiveOperation !is ArchiveOperationState.Running,
                            onClick = { exportLauncher.launch("aurum-export.zip") },
                        ) { Text("Export ZIP") }
                        OutlinedButton(
                            enabled = archiveOperation !is ArchiveOperationState.Running,
                            onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        ) { Text("Import ZIP") }
                    }
                    when (archiveOperation) {
                        ArchiveOperationState.Idle -> Unit
                        is ArchiveOperationState.Running -> {
                            Spacer(Modifier.height(14.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(archiveOperation.message, modifier = Modifier.padding(top = 8.dp))
                        }
                        is ArchiveOperationState.Complete -> {
                            Text(
                                archiveOperation.message,
                                modifier = Modifier.padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is ArchiveOperationState.Failed -> {
                            Text(
                                archiveOperation.message,
                                modifier = Modifier.padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserSettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(modifier = Modifier.semantics { contentDescription = title }, checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsCard(kicker: String, title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(kicker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}