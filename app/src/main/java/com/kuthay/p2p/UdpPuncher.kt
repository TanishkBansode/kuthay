package com.kuthay.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class UdpPuncher(private val sessionId: String) {

    data class Cand(val ip: String, val port: Int)

    sealed class Event {
        data class GotPing(val from: String) : Event()
        data class Connected(val rttMs: Long) : Event()
        data class Text(val msg: String) : Event()
        data class Error(val msg: String) : Event()
    }

    private lateinit var ch: DatagramChannel
    private var localPort = 0
    private var peer: SocketAddress? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var gotPing = false

    fun start() {
        ch = DatagramChannel.open(StandardProtocolFamily.INET6)
        ch.configureBlocking(false)
        ch.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        localPort = (ch.localAddress as InetSocketAddress).port
    }

    fun localPortNumber(): Int = localPort

    suspend fun probe(serverAddr: InetSocketAddress): Cand? {
        repeat(8) { attempt ->
            val payload = JSONObject()
                .put("kuthay", true)
                .put("sid", sessionId)
                .put("seq", attempt)
                .toString()
            try {
                ch.send(ByteBuffer.wrap(payload.toByteArray()), serverAddr)
            } catch (e: Exception) {
                P2pBus.log("probe send error: ${e.message}")
            }
            val deadline = System.currentTimeMillis() + 900
            while (System.currentTimeMillis() < deadline) {
                val bb = ByteBuffer.allocate(1500)
                val from = ch.receive(bb)
                if (from == null) {
                    delay(40)
                    continue
                }
                parseProbeReply(String(bb.array(), 0, bb.position()))?.let { return it }
            }
        }
        return null
    }

    private fun parseProbeReply(text: String): Cand? {
        return try {
            val json = JSONObject(text)
            if (json.optBoolean("kuthay") && json.optString("sid") == sessionId) {
                Cand(json.getString("ip").split("%")[0], json.getInt("port"))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun startPunch(scope: CoroutineScope, cand: Cand, onEvent: (Event) -> Unit) {
        peer = InetSocketAddress(InetAddress.getByName(cand.ip), cand.port)
        running = true
        scope.launch {
            var seq = 0
            while (isActive && running) {
                val msg = if (connected) "K|$sessionId|KA|$seq"
                else "K|$sessionId|PING|$seq|${System.nanoTime()}"
                sendRaw(msg, peer ?: break)
                seq++
                delay(if (connected) 2000L else 350L)
            }
        }
        scope.launch {
            while (isActive && running) {
                val bb = ByteBuffer.allocate(1500)
                val from = ch.receive(bb)
                if (from == null) {
                    delay(25)
                    continue
                }
                handlePacket(String(bb.array(), 0, bb.position()), from, onEvent)
            }
        }
    }

    private fun handlePacket(text: String, from: SocketAddress, onEvent: (Event) -> Unit) {
        if (!text.startsWith("K|$sessionId|")) return
        val p = text.split("|", limit = 5)
        if (p.size < 4) return
        when (p[2]) {
            "PING" -> {
                if (!gotPing) {
                    gotPing = true
                    onEvent(Event.GotPing(from.toString()))
                }
                if (p.size >= 5) sendRaw("K|$sessionId|PONG|${p[4]}", from)
            }
            "PONG" -> {
                if (!connected) {
                    connected = true
                    val rtt = (System.nanoTime() - p[3].toLong()) / 1_000_000
                    onEvent(Event.Connected(rtt))
                }
            }
            "TXT" -> if (p.size == 5) onEvent(Event.Text(p[4]))
        }
    }

    fun sendText(msg: String): Boolean {
        val target = peer ?: return false
        sendRaw("K|$sessionId|TXT|${System.nanoTime()}|$msg", target)
        return connected
    }

    private fun sendRaw(s: String, to: SocketAddress) {
        try {
            ch.send(ByteBuffer.wrap(s.toByteArray()), to)
        } catch (e: Exception) {
            P2pBus.log("udp send error: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            ch.close()
        } catch (_: Exception) {
        }
    }
}
