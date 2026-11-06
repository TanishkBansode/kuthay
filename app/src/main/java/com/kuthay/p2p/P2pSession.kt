package com.kuthay.p2p

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

class P2pSession(
    private val scope: CoroutineScope,
    private val serverUrl: String,
    private val label: String
) {
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(8)
    private lateinit var sig: SignalingClient
    private lateinit var puncher: UdpPuncher
    private var sigReady = false
    private var puncherReady = false
    private var code: String? = null
    @Volatile private var relayMode = false
    private val regAck = CompletableDeferred<JSONObject>()
    private val peerCand = CompletableDeferred<UdpPuncher.Cand>()
    @Volatile private var punchDone: CompletableDeferred<Long>? = null

    fun start(mode: String, joinCode: String) {
        scope.launch {
            try {
                P2pBus.setStatus("CONNECTING_SIGNAL")
                sig = SignalingClient(serverUrl, ::onSignal, ::onSignalDown)
                sig.send(
                    JSONObject()
                        .put("type", if (mode == "host") "register" else "join")
                        .put("code", joinCode)
                        .put("label", label)
                        .put("sid", sessionId)
                        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                )
                val ack = withTimeoutOrNull(12000) { regAck.await() }
                if (ack == null) {
                    fail("no signaling ack")
                    return@launch
                }
                sigReady = true
                val yourAddr = ack.optString("yourAddr")
                if (yourAddr.isNotEmpty()) {
                    P2pBus.log(
                        "signaling via " +
                            if (UdpPuncher.isV4Mapped(yourAddr)) "IPv4 (mapped $yourAddr) — peer likely cannot punch this leg directly"
                            else "IPv6 $yourAddr"
                    )
                }
                puncher = UdpPuncher(sessionId)
                try {
                    puncher.start()
                } catch (e: Exception) {
                    failNote("IPv6 UDP socket unavailable (${e.message}) — this network has no usable IPv6 UDP path (Wi-Fi on? APN Protocol = IPv4?). For Airtel/Vi set APN Protocol to IPv4/IPv6; Jio is IPv6-only and unaffected.")
                    enterRelay()
                    return@launch
                }
                puncherReady = true
                P2pBus.log("udp bound [::]:${puncher.localPortNumber()} sid=$sessionId")

                // NAT64 awareness (Jio 464XLAT): prefer genuine native-IPv6 STUN endpoints;
                // DNS64-synthesised 64:ff9b::/96 AAAA records must not be used — peers
                // outside Jio cannot route them.
                val native = DEFAULT_STUNS.flatMap { (host, port) ->
                    val addrs = runCatching { InetAddress.getAllByName(host) }.getOrDefault(emptyArray<InetAddress>())
                    addrs.filter { it is java.net.Inet6Address }
                        .filterNot { UdpPuncher.isNat64(it.hostAddress ?: "") }
                        .map { InetSocketAddress(it, port) }
                }
                val stunServers = if (native.isNotEmpty()) native else DEFAULT_STUNS.flatMap { (host, port) ->
                    val addrs = runCatching { InetAddress.getAllByName(host) }.getOrDefault(emptyArray<InetAddress>())
                    addrs.map { InetSocketAddress(it, port) }
                }
                if (stunServers.isEmpty()) {
                    failNote("cannot resolve any STUN server (DNS itself is broken?)")
                    enterRelay()
                    return@launch
                }

                val srflx = withTimeoutOrNull(25000) { puncher.probe(stunServers) }
                if (srflx == null) {
                    failNote(
                        if (puncher.sawV4Only)
                            "STUN answered IPv4-only — this network has NO native IPv6 (Wi-Fi on? APN Protocol = IPv4? Check APN Protocol = IPv4/IPv6 for Airtel/Vi; STUN 3478 for Jio NAT64 is native-IPv6 only)."
                        else "STUN unreachable — UDP 3478/19302 blocked or unanswered on this carrier."
                    )
                    enterRelay()
                    return@launch
                }
                if (UdpPuncher.isNat64(srflx.ip)) {
                    failNote("srflx is NAT64-synthesised (${srflx.ip}) — not routable cross-carrier; punch would fail.")
                    enterRelay()
                    return@launch
                }
                P2pBus.log("my srflx = ${srflx.ip}:${srflx.port}")
                sig.send(
                    JSONObject()
                        .put("type", "candidate")
                        .put("ip", srflx.ip)
                        .put("port", srflx.port)
                        .put("sid", sessionId)
                )
                P2pBus.setStatus("WAIT_PEER_CANDIDATE")
                val peer = withTimeoutOrNull(30000) { peerCand.await() }
                if (peer == null) {
                    failNote("timed out waiting for peer candidate — peer is likely IPv4-only (Vi/Airtel tail) or cannot run the punch.")
                    enterRelay()
                    return@launch
                }
                if (UdpPuncher.isNat64(peer.ip)) {
                    failNote("peer candidate is NAT64-synthesised (${peer.ip}) — cross-carrier punch impossible.")
                    enterRelay()
                    return@launch
                }
                P2pBus.log("peer candidate = ${peer.ip}:${peer.port}, punching…")
                P2pBus.setStatus("PUNCHING")
                val done = CompletableDeferred<Long>()
                punchDone = done
                puncher.startPunch(scope, peer, ::onUdpEvent)
                val rtt = withTimeoutOrNull(20000) { done.await() }
                if (rtt == null) {
                    failNote("direct punch failed after 20s (symmetric CGNAT / endpoint-dependent filtering). Falling back to relay…")
                    enterRelay()
                } else {
                    P2pBus.setStatus("CONNECTED (${rtt}ms)")
                    P2pBus.connected.value = true
                    report(true, "connected rtt=${rtt}ms srflx=${srflx.ip}:${srflx.port}")
                }
            } catch (t: Throwable) {
                fail("session error: ${t.message}")
            }
        }
    }

    private fun onUdpEvent(e: UdpPuncher.Event) {
        when (e) {
            is UdpPuncher.Event.GotPing -> P2pBus.log("inbound ping from ${e.from} (half-open)")
            is UdpPuncher.Event.Connected -> {
                if (punchDone?.isCompleted == false) punchDone?.complete(e.rttMs)
            }
            is UdpPuncher.Event.Text -> P2pBus.log("peer: ${e.msg}")
            is UdpPuncher.Event.Error -> P2pBus.log("udp: ${e.msg}")
        }
    }

    private fun onSignal(m: JSONObject) {
        scope.launch {
            when (m.optString("type")) {
                "registered" -> {
                    code = m.optString("code")
                    P2pBus.log("room=$code yourAddr(ws)=${m.optString("yourAddr")}")
                    P2pBus.setStatus("WAIT_PEER_JOIN")
                    if (!regAck.isCompleted) regAck.complete(m)
                }
                "joined" -> {
                    code = m.optString("code")
                    P2pBus.log("joined room=$code yourAddr(ws)=${m.optString("yourAddr")}")
                    if (!regAck.isCompleted) regAck.complete(m)
                }
                "ready" -> P2pBus.log("peer online: ${m.optString("peerInfo")}")
                "candidate" -> {
                    val ip = m.getString("ip").split("%")[0]
                    if (!relayMode) P2pBus.setStatus("GOT_PEER_CANDIDATE")
                    val peerSid = m.optString("sid").ifEmpty { null }
                    if (!peerCand.isCompleted) peerCand.complete(UdpPuncher.Cand(ip, m.getInt("port"), peerSid))
                }
                // Relay fallback push from the peer (their direct path failed/no IPv6).
                "relay-request" -> enterRelay(notifyPeer = false)
                // Relayed chat frame riding the signaling WebSocket.
                "relay-msg" -> P2pBus.log("peer (relayed): ${m.optString("text")}")
                "result" -> P2pBus.log("peer result: ok=${m.optBoolean("ok")} ${m.optString("note")}")
                "error" -> {
                    P2pBus.log("server: ${m.optString("msg")}")
                    P2pBus.setStatus("ERROR")
                }
            }
        }
    }

    private fun onSignalDown(reason: String) {
        P2pBus.log(reason)
        if (!P2pBus.status.value.startsWith("CONNECTED") && !relayMode) P2pBus.setStatus("SIGNAL_DOWN")
    }

    private fun fail(note: String) {
        P2pBus.log("FAIL: $note")
        P2pBus.setStatus("FAILED")
        report(false, note)
    }

    private fun failNote(note: String) {
        P2pBus.log("FAIL: $note")
        report(false, note)
    }

    private fun report(ok: Boolean, note: String) {
        runCatching {
            if (sigReady) sig.send(
                JSONObject()
                    .put("type", "result")
                    .put("ok", ok)
                    .put("note", note)
                    .put("label", label)
                    .put("code", code ?: "")
            )
        }
    }

    // Chat falls back to riding the signaling WebSocket when no direct path exists
    // (symmetric CGNAT / IPv4-only / NAT64). Both peers enter relay mode together.
    private fun enterRelay(notifyPeer: Boolean = true) {
        if (relayMode) return
        relayMode = true
        // Stop the punch storm — those pings would loop forever with nothing to punch to.
        if (puncherReady) runCatching { puncher.stop() }
        if (notifyPeer) {
            runCatching { if (sigReady) sig.send(JSONObject().put("type", "relay-request")) }
        }
        P2pBus.setStatus("RELAYED (signaling fallback)")
        P2pBus.connected.value = true
        P2pBus.log("== RELAYED: direct IPv6 path unavailable; chat now rides the signaling channel ==")
        report(true, "connected via relay fallback")
    }

    fun sendChat(msg: String): Boolean {
        if (msg.isBlank()) return false
        return if (relayMode && sigReady) {
            P2pBus.log("you: $msg")
            sig.send(JSONObject().put("type", "relay-msg").put("text", msg))
        } else if (puncherReady) {
            P2pBus.log("you: $msg")
            puncher.sendText(msg)
        } else false
    }

    fun close() {
        if (puncherReady) runCatching { puncher.stop() }
        if (sigReady) runCatching { sig.close() }
    }

    companion object {
        // Standard STUN port first (3478) — some carrier CGNs treat non-standard
        // 19302 differently (e.g. Airtel). Google 19302 kept as a second opinion.
        val DEFAULT_STUNS = listOf(
            "stun.cloudflare.com" to 3478,
            "stun.l.google.com" to 19302
        )
    }
}
