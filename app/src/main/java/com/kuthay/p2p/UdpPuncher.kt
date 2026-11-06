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

    data class Cand(val ip: String, val port: Int, val peerSid: String? = null)

    sealed class Event {
        data class GotPing(val from: String) : Event()
        data class Connected(val rttMs: Long) : Event()
        data class Text(val msg: String) : Event()
        data class Error(val msg: String) : Event()
    }

    private var ch: DatagramChannel? = null
    private var localPort = 0
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var peerSid: String? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var gotPing = false
    @Volatile var sawV4Only = false
        private set

    fun start() {
        val c = DatagramChannel.open(StandardProtocolFamily.INET6)
        c.configureBlocking(false)
        c.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        ch = c
        localPort = (c.localAddress as InetSocketAddress).port
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
        val c = ch ?: return null
        val txid = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val req = ByteBuffer.allocate(20)
            .putShort(0x0001)
            .putShort(0)
            .putInt(MAGIC_COOKIE)
            .put(txid)
        req.flip()
        try {
            c.send(req, srv)
        } catch (e: Exception) {
            P2pBus.log("stun send error: ${e.message}")
            return null
        }
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            val bb = ByteBuffer.allocate(1500)
            val from = try {
                c.receive(bb)
            } catch (e: Exception) {
                P2pBus.log("stun recv error: ${e.message}")
                return null
            }
            if (from == null) {
                delay(40)
                continue
            }
            try {
                parseStunResponse(bb, txid)?.let { return it }
            } catch (e: Exception) {
                P2pBus.log("stun parse error: ${e.message}")
                return null
            }
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

        // RFC 5389 §15.2: full 16-byte XOR mask = magic cookie ++ transaction ID.
        val mask = ByteArray(16)
        System.arraycopy(MAGIC_BYTES, 0, mask, 0, 4)
        System.arraycopy(txid, 0, mask, 4, 12)

        while (bb.remaining() >= 4) {
            val attrType = bb.short.toInt() and 0xFFFF
            val attrLen = bb.short.toInt() and 0xFFFF
            if (attrLen > bb.remaining()) break
            val start = bb.position()
            if (attrType == 0x0020 && attrLen >= 8 && start + attrLen <= bb.limit()) {
                val family = bb.get(start + 1).toInt()
                var port = (bb.get(start + 2).toInt() and 0xFF) shl 8 or (bb.get(start + 3).toInt() and 0xFF)
                port = port xor (MAGIC_COOKIE ushr 16)
                if (family == 2 && attrLen == 20) {
                    val addr = ByteArray(16)
                    for (j in 0 until 16) {
                        addr[j] = (bb.get(start + 4 + j).toInt() xor mask[j].toInt()).toByte()
                    }
                    val ip = InetAddress.getByAddress(addr).hostAddress?.split("%")?.get(0)
                    if (ip != null && !ip.contains(":ffff:") && !isNat64(ip)) return Cand(ip, port)
                } else if (family == 1 && attrLen == 8) {
                    sawV4Only = true
                }
            }
            bb.position(minOf(start + ((attrLen + 3) / 4) * 4, bb.limit()))
        }
        return null
    }

    fun startPunch(scope: CoroutineScope, cand: Cand, onEvent: (Event) -> Unit) {
        peer = InetSocketAddress(InetAddress.getByName(cand.ip), cand.port)
        peerSid = cand.peerSid
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
                val c = ch ?: break
                val bb = ByteBuffer.allocate(1500)
                val from = try {
                    c.receive(bb)
                } catch (e: Exception) {
                    if (running) P2pBus.log("udp recv error: ${e.message}")
                    break
                }
                if (from == null) {
                    delay(25)
                    continue
                }
                try {
                    handlePacket(String(bb.array(), 0, bb.position()), from, onEvent)
                } catch (e: Exception) {
                    P2pBus.log("udp packet error: ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(text: String, from: SocketAddress, onEvent: (Event) -> Unit) {
        // Frame must carry the peer's session id (learned over signalling). This is the
        // primary anti-spoof gate. If the sid was never learned (legacy frame), accept any
        // K| frame as a compat fallback — documented.
        val sid = peerSid
        if (sid != null) {
            if (!text.startsWith("K|$sid|")) return
        } else if (!text.startsWith("K|")) return
        val parts = text.split("|", limit = 5)
        if (parts.size < 4) return
        // Address-dependent CGNs (common for Indian carriers) re-roll the peer's source
        // port per destination, so the punch can arrive from a different port than the
        // peer's STUN candidate. While punching, adopt the OBSERVED source (must share the
        // same IP) instead of dropping it. After CONNECTED, return to strict matching.
        if (from != peer) {
            val sameHost = (from as? InetSocketAddress)?.address?.hostAddress?.split("%")?.get(0) ==
                (peer as? InetSocketAddress)?.address?.hostAddress?.split("%")?.get(0)
            if (!connected && sid != null && sameHost) {
                P2pBus.log("adopting peer's observed source $from (CGN remapped port per destination)")
                peer = from
            } else {
                return
            }
        }
        when (parts[2]) {
            "PING" -> {
                if (!gotPing) {
                    gotPing = true
                    onEvent(Event.GotPing(from.toString()))
                }
                if (parts.size >= 5) sendRaw("K|$sessionId|PONG|${parts[4]}", from)
            }
            "PONG" -> {
                if (!connected) {
                    val sent = parts[3].toLongOrNull() ?: return
                    connected = true
                    val rtt = (System.nanoTime() - sent) / 1_000_000
                    onEvent(Event.Connected(rtt))
                }
            }
            "TXT" -> if (parts.size == 5) onEvent(Event.Text(parts[4]))
        }
    }

    fun sendText(msg: String): Boolean {
        val target = peer ?: return false
        sendRaw("K|$sessionId|TXT|${System.nanoTime()}|$msg", target)
        return connected
    }

    private fun sendRaw(s: String, to: SocketAddress) {
        try {
            ch?.send(ByteBuffer.wrap(s.toByteArray()), to)
        } catch (e: Exception) {
            P2pBus.log("udp send error: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            ch?.close()
        } catch (_: Exception) {
        }
        ch = null
    }

    companion object {
        private const val MAGIC_COOKIE = 0x2112A442
        private val MAGIC_BYTES = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42.toByte())

        // 64:ff9b::/96 — DNS64/NAT64 well-known synthesis prefix (Jio 464XLAT).
        // Such addresses are only routable inside the synthesising carrier;
        // advertising or punching them cross-carrier fails deterministically.
        // Handles both compressed ("64:ff9b::a00:1") and uncompressed ("64:ff9b:0:0:0:0:a00:1") forms.
        fun isNat64(ip: String): Boolean {
            val s = ip.lowercase().split("%")[0]
            return s.startsWith("64:ff9b::") || s.startsWith("64:ff9b:0:0:0:0:")
        }

        fun isV4Mapped(ip: String): Boolean = ip.contains(":ffff:")
    }
}
