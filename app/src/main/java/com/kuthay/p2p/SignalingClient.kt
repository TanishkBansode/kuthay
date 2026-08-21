package com.kuthay.p2p

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(
    url: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onDown: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null

    init {
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        onMessage(JSONObject(text))
                    } catch (e: Exception) {
                        P2pBus.log("bad signal frame: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onDown("signaling failed: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onDown("signaling closed ($code)")
                }
            }
        )
    }

    fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

    fun close() {
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        client.dispatcher.executorService.shutdown()
    }
}
