package com.payra.gateway.network

import com.payra.gateway.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class HeartbeatInterval(val seconds: Int) {
    THIRTY_SECONDS(30),
    ONE_MINUTE(60),
    TWO_MINUTES(120),
}

internal data class GatewayServer(
    val baseUrl: HttpUrl,
    val heartbeat: HeartbeatInterval,
) {
    val socketUrl: HttpUrl
        get() = baseUrl.newBuilder()
            .addPathSegment("gateway")
            .build()

    companion object {
        fun parseBaseUrl(input: String): HttpUrl? {
            val address = input.trim()
            val scheme = address.substringBefore("://").lowercase()
            if (scheme != "https" && !(BuildConfig.DEBUG && scheme == "http")) return null
            if (address.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null

            val authority = address.substringAfter("://", "").substringBefore('/')
            if (authority.isEmpty() || '@' in authority || '?' in address || '#' in address) return null
            val url = address.toHttpUrlOrNull() ?: return null
            return url.newBuilder()
                .encodedPath(url.encodedPath.trimEnd('/').ifEmpty { "/" })
                .build()
        }
    }
}
