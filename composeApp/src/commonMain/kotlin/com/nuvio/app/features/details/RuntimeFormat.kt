package com.nuvio.app.features.details

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_runtime_hours_minutes
import nuvio.composeapp.generated.resources.details_runtime_hours_only
import nuvio.composeapp.generated.resources.details_runtime_minutes_only
import org.jetbrains.compose.resources.getString

private val hourTokenRegex = Regex("""(?i)(\d+)\s*h(?:ours?)?""")
private val minuteTokenRegex = Regex("""(?i)(\d+)\s*m(?:in(?:ute)?s?)?""")
private val hourMinuteColonRegex = Regex("""^\s*(\d+)\s*:\s*(\d{1,2})\s*$""")
private val digitsOnlyRegex = Regex("""^\s*(\d+)\s*$""")

internal fun formatRuntimeForDisplay(rawRuntime: String?): String? {
    val normalized = rawRuntime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val totalMinutes = parseRuntimeMinutes(normalized) ?: return normalized
    return formatRuntimeFromMinutes(totalMinutes)
}

internal fun formatRuntimeFromMinutes(totalMinutes: Int): String {
    if (totalMinutes <= 0) return ""
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return runBlocking {
        when {
            hours > 0 && minutes > 0 -> getString(Res.string.details_runtime_hours_minutes, hours, minutes)
            hours > 0 -> getString(Res.string.details_runtime_hours_only, hours)
            else -> getString(Res.string.details_runtime_minutes_only, minutes)
        }
    }
}

internal fun parseRuntimeMinutes(rawRuntime: String?): Int? {
    val value = rawRuntime?.trim()?.takeIf(String::isNotBlank) ?: return null
    hourMinuteColonRegex.matchEntire(value)?.let { match ->
        val hours = match.groupValues[1].toIntOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull() ?: return null
        return (hours * 60) + minutes
    }

    val hoursToken = hourTokenRegex.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutesToken = minuteTokenRegex.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (hoursToken != null || minutesToken != null) {
        val hours = (hoursToken ?: 0).coerceAtLeast(0)
        val minutes = (minutesToken ?: 0).coerceAtLeast(0)
        return (hours * 60) + minutes
    }

    digitsOnlyRegex.matchEntire(value)?.let { match ->
        return match.groupValues[1].toIntOrNull()?.coerceAtLeast(0)
    }

    return null
}
