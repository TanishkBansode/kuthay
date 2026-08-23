package com.kuthay.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    suspend fun probe(stunServers: List<InetSocketAddress>): Cand? {
        for (srv in stunServers) {
            P2pBus.log("stun query ${srv.address.hostAddress}:${srv.port}")
            repeat(3) {
                stunQueryOnce(srv)?.let { return it }
                delay(300)
            }
        }
        return null
    }

    private suspend fun stunQueryOnce(srv: InetSocketAddress): Cand? {
        val txid = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val req = ByteBuffer.allocate(20)
            .putShort(0x0001)
            .putShort(0)
            .putInt(MAGIC_COOKIE)
            .put(txid)
        req.flip()
        try {
            ch.send(req, srv)
        } catch (e: Exception) {
            P2pBus.log("stun send error: ${e.message}")
            return null
        }
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            val bb = ByteBuffer.allocate(1500)
            val from = ch.receive(bb)
            if (from == null) {
                delay(40)
                continue
            }
            parseStunResponse(bb, txid)?.let { return it }
        }
        return null
    }

    private fun parseStunResponse(bb: ByteBuffer, txid: ByteArray): Cand? {
        if (bb.position() < 20) return null
        bb.flip()
        val type = bb.short.toInt() and 0xFFFF
        if (type != 0x0101) return null
        bb.getShort()
        for (i in 0 until 4) if (bb.get() != MAGIC_BYTES[i]) return null
        val rxTid = ByteArray(12).also { bb.get(it) }
        if (!rxTid.contentEquals(txid)) return null

        while (bb.remaining() >= 4) {
            val attrType = bb.short.toInt() and 0xFFFF
            val attrLen = bb.short.toInt() and 0xFFFF
            if (attrLen > bb.remaining()) break
            val start = bb.position()
            if (attrType == 0x0020 && attrLen >= 8) {
                val family = bb.get(start + 1).toInt()
                var port = (bb.get(start + 2).toInt() and 0xFF) shl 8 or (bb.get(start + 3).toInt() and 0xFF)
                port = port xor (MAGIC_COOKIE ushr 16)
                if (family == 2 && attrLen == 20) {
                    val addr = ByteArray(16)
                    for (j in 0 until 16) {
                        addr[j] = (bb.get(start + 4 + j).toInt() xor MAGIC_BYTES[j % 4].toInt()).toByte()
                    }
                    val ip = InetAddress.getByAddress(addr).hostAddress?.split("%")?.get(0)
                    if (ip != null && !ip.contains(":ffff:")) return Cand(ip, port)
                }
            }
            bb.position(start + ((attrLen + 3) / 4) * 4)
        }
        return null
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

    companion object {
        private const val MAGIC_COOKIE = 0x2112A444
        private val MAGIC_BYTES = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x44.toByte())
    }
}
