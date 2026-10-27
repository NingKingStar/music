package com.muses.player.core.search.http

internal actual fun searchRetryDelay(value: String?, nowMs: Long): Long {
    value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let {
        return (it.coerceAtMost(86_400L * 365) * 1_000).coerceAtLeast(1_000)
    }
    return try {
        (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - nowMs).coerceAtLeast(1_000)
    } catch (_: Exception) { 60_000L }
}
