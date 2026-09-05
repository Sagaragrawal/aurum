package com.aurum.intelligence.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.intelligence.data.BullionBenchmark
import com.aurum.intelligence.data.BullionHistoryEntity
import com.aurum.intelligence.data.BullionRatePolicy
import com.aurum.intelligence.ui.theme.AurumGold
import com.aurum.intelligence.ui.theme.AurumGold2
import com.aurum.intelligence.ui.theme.AurumLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class TrendPoint(val timestamp: Long, val price24: Double, val price22: Double)

object BullionTrendSeries {
    fun build(history: List<BullionHistoryEntity>, limit: Int = 120): List<TrendPoint> = history
        .groupBy(BullionHistoryEntity::fetchedAt)
        .mapNotNull { (timestamp, rows) ->
            val price24 = BullionBenchmark.blend(rows.map(BullionHistoryEntity::price24).filter(BullionRatePolicy::isPlausible24))
                ?: return@mapNotNull null
            val price22 = BullionBenchmark.blend(rows.map(BullionHistoryEntity::price22).filter { price ->
                BullionRatePolicy.isPlausible22(price, price24)
            }) ?: return@mapNotNull null
            TrendPoint(timestamp, price24, price22)
        }
        .sortedBy(TrendPoint::timestamp)
        .takeLast(limit)
}

@Composable
fun BullionTrendCard(history: List<BullionHistoryEntity>) {
    val points = BullionTrendSeries.build(history)
    var show24 by rememberSaveable { mutableStateOf(true) }
    var show22 by rememberSaveable { mutableStateOf(true) }
    val visibleValues = buildList {
        if (show24) addAll(points.map(TrendPoint::price24))
        if (show22) addAll(points.map(TrendPoint::price22))
    }
    val low = visibleValues.minOrNull()
    val high = visibleValues.maxOrNull()
    val primary = if (show24) points.map(TrendPoint::price24) else points.map(TrendPoint::price22)
    val change = primary.takeIf { it.size > 1 }?.let { it.last() - it.first() }
    Card(
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, AurumLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("GOLD RATE TREND", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    Text("Blended price history", fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = show24, onClick = { if (!show24 || show22) show24 = !show24 }, label = { Text("24K") })
                    FilterChip(selected = show22, onClick = { if (!show22 || show24) show22 = !show22 }, label = { Text("22K") })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low ${formatTrendRate(low)}", style = MaterialTheme.typography.bodySmall)
                Text("High ${formatTrendRate(high)}", style = MaterialTheme.typography.bodySmall)
                Text("Change ${change?.let { String.format(Locale.US, "%+.0f", it) } ?: "--"}", style = MaterialTheme.typography.bodySmall)
            }
            if (points.isNotEmpty()) {
                Text(
                    "${formatTrendDate(points.first().timestamp)} to ${formatTrendDate(points.last().timestamp)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (points.size < 2 || low == null || high == null) {
                Text("Refresh bullion rates to build the line graph.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val seriesLabel = when {
                    show24 && show22 -> "24K and 22K gold"
                    show24 -> "24 karat gold"
                    else -> "22 karat gold"
                }
                val latest = primary.lastOrNull()
                val chartDescription = "$seriesLabel trend from ${formatTrendAxisDay(points.first().timestamp)} to " +
                    "${formatTrendAxisDay(points.last().timestamp)}. Low ${formatTrendRate(low)} per gram, high ${formatTrendRate(high)} per gram, " +
                    "latest ${formatTrendRate(latest)} per gram."
                TrendCanvas(points, show24, show22, low, high, chartDescription)
            }
        }
    }
}

@Composable
private fun TrendCanvas(points: List<TrendPoint>, show24: Boolean, show22: Boolean, low: Double, high: Double, chartDescription: String) {
    val gridColor = AurumLine
    val minimum = low * 0.95
    val maximum = high * 1.05
    var canvasWidthPx by remember { mutableStateOf(0f) }
    // Tap/drag inspection (item 8A): selects the nearest observation by x-position only; does not
    // add a semantics node per point (item 8B asks for one chart-level summary, not dozens).
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    fun selectNearest(x: Float) {
        if (points.isEmpty() || canvasWidthPx <= 0f) return
        val ratio = (x / canvasWidthPx).coerceIn(0f, 1f)
        selectedIndex = (ratio * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(160.dp)) {
            Column(Modifier.width(58.dp).height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(formatTrendRate(maximum), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTrendRate((minimum + maximum) / 2), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTrendRate(minimum), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .onSizeChanged { canvasWidthPx = it.width.toFloat() }
                    .pointerInput(points) { detectTapGestures { offset -> selectNearest(offset.x) } }
                    .pointerInput(points) {
                        detectDragGestures(onDragStart = { selectNearest(it.x) }) { change, _ ->
                            selectNearest(change.position.x)
                            change.consume()
                        }
                    }
                    .clearAndSetSemantics { contentDescription = chartDescription },
            ) {
        val span = (maximum - minimum).takeIf { it > 0 } ?: 1.0
        repeat(3) { row ->
            val y = size.height * row / 2f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        fun path(values: List<Double>): Path = Path().apply {
            values.forEachIndexed { index, price ->
                val x = if (values.size == 1) 0f else size.width * index / (values.size - 1f)
                val y = size.height - (((price - minimum) / span) * size.height).toFloat()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        if (show24) drawPath(path(points.map(TrendPoint::price24)), AurumGold, style = Stroke(width = 4f))
        if (show22) drawPath(path(points.map(TrendPoint::price22)), AurumGold2, style = Stroke(width = 4f))
        selectedIndex?.let { index ->
            val x = if (points.size == 1) 0f else size.width * index / (points.size - 1f)
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
            }
        }
        selectedIndex?.let { index ->
            val point = points[index]
            Text(
                "${formatTrendDate(point.timestamp)} \u00b7 24K ${formatTrendRate(point.price24)} \u00b7 22K ${formatTrendRate(point.price22)}",
                modifier = Modifier.padding(start = 58.dp, top = 4.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 58.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(points.first(), points[points.size / 2], points.last()).forEach { point ->
                Text(formatTrendAxisDate(point.timestamp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

private fun formatTrendRate(value: Double?): String = value?.let { "Rs ${it.toLong()}" } ?: "--"

private fun formatTrendDate(timestamp: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(timestamp))

private fun formatTrendAxisDate(timestamp: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(timestamp))

private fun formatTrendAxisDay(timestamp: Long): String =
    SimpleDateFormat("d MMMM", Locale.US).format(Date(timestamp))