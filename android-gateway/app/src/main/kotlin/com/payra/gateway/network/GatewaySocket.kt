package com.payra.gateway.network

import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

internal enum class GatewayConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    RECONNECTING,
}

internal class GatewaySocket(
    private val connectivity: ConnectivityManager,
    private val client: OkHttpClient,
) {
    // The service owns this call. Cancelling it also cancels the socket and any retry delay.
    suspend fun monitor(
        server: GatewayServer,
        beforeConnect: () -> Boolean,
        onStatus: (GatewayConnectionStatus) -> Unit,
        onWorkAvailable: () -> Unit,
    ) {
        val socketClient = client.newBuilder()
            .pingInterval(server.heartbeat.seconds.toLong(), TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(server.socketUrl).build()
        var attempted = false

        observeDefaultNetwork(connectivity).collectLatest { network ->
            if (network == null) {
                onStatus(GatewayConnectionStatus.OFFLINE)
                return@collectLatest
            }

            var retryDelay = INITIAL_RETRY_MILLIS
            while (currentCoroutineContext().isActive && beforeConnect()) {
                onStatus(if (attempted) GatewayConnectionStatus.RECONNECTING else GatewayConnectionStatus.CONNECTING)
                attempted = true
                var openedAt: Long? = null
                socketEvents(socketClient, request).collect { event ->
                    when (event) {
                        SocketEvent.OPENED -> {
                            openedAt = SystemClock.elapsedRealtime()
                            Log.i(TAG, "WebSocket connected")
                            onStatus(GatewayConnectionStatus.CONNECTED)
                        }
                        SocketEvent.WORK_AVAILABLE -> {
                            Log.i(TAG, "Received work_available")
                            onWorkAvailable()
                        }
                    }
                }

                if (openedAt?.let { SystemClock.elapsedRealtime() - it >= MAX_RETRY_MILLIS } == true) {
                    retryDelay = INITIAL_RETRY_MILLIS
                }
                onStatus(GatewayConnectionStatus.RECONNECTING)
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    private fun socketEvents(client: OkHttpClient, request: Request): Flow<SocketEvent> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(SocketEvent.OPENED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = try {
                    JSONObject(text).optString("type")
                } catch (_: JSONException) {
                    return
                }
                if (type == "work_available") trySend(SocketEvent.WORK_AVAILABLE)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed (code $code)")
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (close()) {
                    Log.w(TAG, "WebSocket failed (${response?.code ?: t.javaClass.simpleName})")
                }
            }
        }
        val socket = client.newWebSocket(request, listener)
        awaitClose { socket.cancel() }
    }

    private enum class SocketEvent { OPENED, WORK_AVAILABLE }

    companion object {
        private const val TAG = "GatewaySocket"
        private const val INITIAL_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 30_000L
    }
}
