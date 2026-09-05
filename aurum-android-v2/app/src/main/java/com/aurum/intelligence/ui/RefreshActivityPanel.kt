package com.aurum.intelligence.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.intelligence.data.RefreshActivityLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun RefreshActivityPanel(
    logs: List<RefreshActivityLogEntity>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var copied by rememberSaveable { mutableStateOf(false) }
    var severityFilter by rememberSaveable { mutableStateOf<RefreshLogFilter?>(null) }
    var storeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    var followNewLogs by remember { mutableStateOf(true) }
    val stores = logs.mapNotNull(RefreshActivityLogEntity::store).distinct().sorted()
    val visibleLogs = logs.filter { log ->
        (severityFilter == null || log.severity == severityFilter!!.severity) &&
            (storeFilter == null || log.store == storeFilter)
    }
    val clipboardManager = LocalContext.current.getSystemService(ClipboardManager::class.java)
    androidx.compose.runtime.LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) {
            followNewLogs = scrollState.value >= scrollState.maxValue
        }
    }
    androidx.compose.runtime.LaunchedEffect(visibleLogs.size) {
        if (expanded && followNewLogs) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "Refresh Activity (${visibleLogs.size}/${logs.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                TextButton(
                    onClick = {
                        clipboardManager?.setPrimaryClip(
                            ClipData.newPlainText("Aurum Refresh Activity", visibleLogs.joinToString("\n", transform = ::formatLog)),
                        )
                        copied = true
                    },
                    enabled = visibleLogs.isNotEmpty(),
                ) { Text(if (copied) "Copied" else "Copy") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
                TextButton(onClick = onClear, enabled = logs.isNotEmpty()) { Text("Clear") }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                RefreshLogFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = severityFilter == filter,
                        onClick = { severityFilter = if (severityFilter == filter) null else filter },
                        label = { Text(filter.label) },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                stores.forEach { store ->
                    FilterChip(
                        selected = storeFilter == store,
                        onClick = { storeFilter = if (storeFilter == store) null else store },
                        label = { Text(store) },
                    )
                }
            }
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (visibleLogs.isEmpty()) {
                        Text(if (logs.isEmpty()) "No refresh activity yet." else "No matching refresh activity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    visibleLogs.forEach { log ->
                        val severityColor = when (log.severity) {
                            "error" -> MaterialTheme.colorScheme.error
                            "warning" -> Color(0xFFFFB74D)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = "${formatLogTime(log.timestamp)} ${log.severity.uppercase()}",
                                color = severityColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                            Text(
                                text = " [${log.store ?: "system"}] ",
                                color = storeColor(log.store),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                            Text(
                                text = log.message,
                                modifier = Modifier.weight(1f),
                                color = severityColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class RefreshLogFilter(val label: String, val severity: String) {
    Error("Errors", "error"),
    Warning("Warnings", "warning"),
    Info("Info", "info"),
}

private fun storeColor(store: String?): Color = when (store) {
    "ajio.com" -> Color(0xFFE0A100)
    "amazon.in" -> Color(0xFF5DADE2)
    "flipkart.com" -> Color(0xFF46C2B8)
    "myntra.com" -> Color(0xFFFF6B9A)
    "shopsy.in" -> Color(0xFF8E44AD)
    "tanishq" -> Color(0xFFF0B429)
    else -> Color(0xFFB0B8C2)
}

private fun formatLogTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))

private fun formatLog(log: RefreshActivityLogEntity): String =
    "${formatLogTime(log.timestamp)} ${log.severity.uppercase()}" +
        log.store?.let { " [$it]" }.orEmpty() + " ${log.message}"