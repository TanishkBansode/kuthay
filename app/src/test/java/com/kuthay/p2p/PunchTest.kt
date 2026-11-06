package com.kuthay.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer

class PunchTest {

    private val txid = ByteArray(12) { (it + 1).toByte() }

    private fun buildResponse(family: Int, plainAddr: ByteArray, plainPort: Int): ByteBuffer {
        // value length only: 1 (reserved) + 1 (family) + 2 (port) + 4 (IPv4) = 8; 4+16 = 20 for IPv6
        val attrLen = if (family == 2) 20 else 8
        val total = 20 + 4 + attrLen
        val buf = ByteBuffer.allocate(total)
        buf.putShort(0x0101)          // binding success response
        buf.putShort((4 + attrLen).toShort())
        buf.put(byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42))   // magic cookie
        buf.put(txid)                                            // transaction id
        buf.putShort(0x0020)                                    // XOR-MAPPED-ADDRESS
        buf.putShort(attrLen.toShort())
        buf.put(0x00)
        buf.put(family.toByte())
        val xport = plainPort xor 0x2112
        buf.putShort((xport and 0xFFFF).toShort())
        val mask = ByteArray(16)
        mask[0] = 0x21; mask[1] = 0x12; mask[2] = 0xA4.toByte(); mask[3] = 0x42
        System.arraycopy(txid, 0, mask, 4, 12)
        val addrLen = if (family == 2) 16 else 4
        for (j in 0 until addrLen) buf.put((plainAddr[j].toInt() xor mask[j].toInt()).toByte())
        buf.position(total)
        return buf
    }

    private fun parse(puncher: UdpPuncher, bb: ByteBuffer): UdpPuncher.Cand? {
        val m = UdpPuncher::class.java.getDeclaredMethod(
            "parseStunResponse", ByteBuffer::class.java, ByteArray::class.java
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(puncher, bb, txid) as UdpPuncher.Cand?
    }

    @Test
    fun `ipv6 XOR-MAPPED is decoded with full cookie+txid mask`() {
        val plainAddr = byteArrayOf(
            0x24, 0x05, 0x02, 0x01, 0x90.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0xf3.toByte(), 0xe1.toByte()
        )
        val puncher = UdpPuncher("00000000")
        val cand = parse(puncher, buildResponse(2, plainAddr, 41234))
        assertEquals(InetAddress.getByAddress(plainAddr).hostAddress, cand?.ip)
        assertEquals(41234, cand?.port)
        assertFalse(puncher.sawV4Only)
    }

    @Test
    fun `ipv4-only answer sets sawV4Only and yields no candidate`() {
        val puncher = UdpPuncher("00000000")
        val cand = parse(puncher, buildResponse(1, byteArrayOf(49, 36, 1, 2), 40000))
        assertNull(cand)
        assertTrue(puncher.sawV4Only)
    }

    @Test
    fun `malformed attribute does not throw and yields null`() {
        val total = 20 + 4 + 8
        val buf = ByteBuffer.allocate(total)
        buf.putShort(0x0101)
        buf.putShort(12)
        buf.put(byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42))
        buf.put(txid)
        buf.putShort(0x0020)
        buf.putShort(0x7fff)   // absurd attrLen: only 8 bytes of body follow
        buf.putLong(0)
        buf.position(total)
        val puncher = UdpPuncher("00000000")
        assertNull(parse(puncher, buf))
    }

    @Test
    fun `odd-length attribute with two-byte padding is skipped correctly`() {
        val plainAddr = byteArrayOf(
            0x24, 0x05, 0x02, 0x01, 0x90.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0xf3.toByte(), 0xe1.toByte()
        )
        val mask = ByteArray(16)
        mask[0] = 0x21; mask[1] = 0x12; mask[2] = 0xA4.toByte(); mask[3] = 0x42
        System.arraycopy(txid, 0, mask, 4, 12)
        val attr1 = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01, 0x41, 0x00, 0x00, 0x00)  // odd len=1 + 3 pad
        val attr2Body = ByteBuffer.allocate(20)
        attr2Body.put(0x00)
        attr2Body.put(2)                                             // family 2
        attr2Body.putShort(((41234 xor 0x2112) and 0xFFFF).toShort())
        for (j in 0 until 16) attr2Body.put((plainAddr[j].toInt() xor mask[j].toInt()).toByte())
        val attr2 = ByteBuffer.allocate(4 + 20).putShort(0x0020).putShort(20).put(attr2Body.array()).array()
        val body = attr1 + attr2
        val buf = ByteBuffer.allocate(20 + body.size)
        buf.putShort(0x0101)
        buf.putShort(body.size.toShort())
        buf.put(byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42))
        buf.put(txid)
        buf.put(body)
        buf.position(buf.capacity())
        val puncher = UdpPuncher("00000000")
        val cand = parse(puncher, buf)
        assertEquals(InetAddress.getByAddress(plainAddr).hostAddress, cand?.ip)
        assertEquals(41234, cand?.port)
    }

    @Test
    fun `ADM-remapped source is adopted while punching`() {
        val events = mutableListOf<UdpPuncher.Event>()
        val puncher = UdpPuncher("ownSid11")
        val ip = InetAddress.getByName("2405:201:9002::f3e1")
        val candidatePort = InetSocketAddress(ip, 40000)   // srflx from STUN
        val observed = InetSocketAddress(ip, 40002)        // what our ping actually arrives on (ADM CGN)
        setField(puncher, "peer", candidatePort)
        setField(puncher, "peerSid", "peerSid22")
        invokeHandle(puncher, "K|peerSid22|PING|7|123", observed) { events.add(it) }
        assertEquals(observed, getField(puncher, "peer"))
        assertEquals(1, events.size)   // GotPing delivered, frame NOT dropped
    }

    @Test
    fun `transfer is strict once connected`() {
        val events = mutableListOf<UdpPuncher.Event>()
        val puncher = UdpPuncher("ownSid11")
        val ip = InetAddress.getByName("2405:201:9002::f3e1")
        val peer = InetSocketAddress(ip, 40000)
        setField(puncher, "peer", peer)
        setField(puncher, "peerSid", "peerSid22")
        setField(puncher, "connected", true)
        val foreign = InetSocketAddress(InetAddress.getByName("2405:201::dead"), 40000)
        invokeHandle(puncher, "K|peerSid22|TXT|1|hello", foreign) { events.add(it) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `nat64 well-known prefix detected in compressed and uncompressed forms`() {
        assertTrue(UdpPuncher.isNat64("64:ff9b::a00:1"))
        assertTrue(UdpPuncher.isNat64("64:ff9b:0:0:0:0:a00:1"))
        assertTrue(UdpPuncher.isNat64("64:FF9B::A00:1"))
        assertFalse(UdpPuncher.isNat64("2405:201:9002::f3e1"))
        assertFalse(UdpPuncher.isNat64("::ffff:49.36.1.2"))
    }

    @Test
    fun `packets from non-peer source are dropped`() {
        val events = mutableListOf<UdpPuncher.Event>()
        val puncher = UdpPuncher("ownSid11")
        val peerIp = InetAddress.getByName("2405:201:9002::f3e1")
        val peer = InetSocketAddress(peerIp, 40000)
        setField(puncher, "peer", peer)
        setField(puncher, "peerSid", "peerSid22")

        val wrong = InetSocketAddress(InetAddress.getByName("2405:201::dead"), 40000)
        invokeHandle(puncher, "K|peerSid22|TXT|1|hello", wrong) { events.add(it) }
        assertTrue(events.isEmpty())

        invokeHandle(puncher, "K|otherSid99|TXT|1|spoof", peer) { events.add(it) }
        assertTrue(events.isEmpty())

        invokeHandle(puncher, "K|peerSid22|TXT|1|hello", peer) { events.add(it) }
        assertEquals(1, events.size)
        assertEquals(UdpPuncher.Event.Text("hello"), events.first())
    }

    @Test
    fun `punch session completes between two INET6 sockets on loopback`() {
        // Manual sanity: two raw sockets can exchange K| frames (what the
        // punch does without NAT in the middle), guard against protocol regressions.
        DatagramSocket(0, InetAddress.getByName("::1")).use { a ->
            DatagramSocket(0, InetAddress.getByName("::1")).use { b ->
                val token = "K|peerSid22|PING|7|123456789"
                val bytes = token.toByteArray()
                a.send(java.net.DatagramPacket(bytes, bytes.size, b.localAddress, b.localPort))
                val buf = ByteArray(512)
                b.soTimeout = 1000
                val pkt = java.net.DatagramPacket(buf, buf.size)
                b.receive(pkt)
                assertEquals(token, String(pkt.data, 0, pkt.length))
            }
        }
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(obj, value)
    }

    private fun getField(obj: Any, name: String): Any? {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(obj)
    }

    private fun invokeHandle(puncher: UdpPuncher, text: String, from: SocketAddress, cb: (UdpPuncher.Event) -> Unit) {
        val m = UdpPuncher::class.java.getDeclaredMethod(
            "handlePacket", String::class.java, SocketAddress::class.java,
            kotlin.jvm.functions.Function1::class.java
        )
        m.isAccessible = true
        m.invoke(puncher, text, from, cb)
    }
}
