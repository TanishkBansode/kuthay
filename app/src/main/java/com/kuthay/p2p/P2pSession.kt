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
    private var code: String? = null
    private val regAck = CompletableDeferred<JSONObject>()
    private val peerCand = CompletableDeferred<UdpPuncher.Cand>()
    private val punchDone = CompletableDeferred<Long>()

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
                        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                )
                val ack = withTimeoutOrNull(12000) { regAck.await() }
                if (ack == null) {
                    fail("no signaling ack")
                    return@launch
                }
                puncher = UdpPuncher(sessionId)
                puncher.start()
                P2pBus.log("udp bound [::]:${puncher.localPortNumber()} sid=$sessionId")
                val stunServers = DEFAULT_STUNS.flatMap { (host, port) ->
                    val addrs = runCatching { InetAddress.getAllByName(host) }.getOrDefault(arrayOf())
                    addrs.sortedByDescending { it is java.net.Inet6Address }
                        .map { InetSocketAddress(it, port) }
                }
                if (stunServers.isEmpty()) {
                    fail("cannot resolve any STUN server")
                    return@launch
                }
                val srflx = withTimeoutOrNull(25000) { puncher.probe(stunServers) }
                if (srflx == null) {
                    fail(
                        if (puncher.sawV4Only) "STUN answered with IPv4 only — this network has NO IPv6 (Wi-Fi on? APN set to IPv4?)"
                        else "STUN unreachable — UDP 19302/3478 blocked?"
                    )
                    return@launch
                }
                P2pBus.log("my srflx = ${srflx.ip}:${srflx.port}")
                sig.send(
                    JSONObject().put("type", "candidate").put("ip", srflx.ip).put("port", srflx.port)
                )
                P2pBus.setStatus("WAIT_PEER_CANDIDATE")
                val peer = withTimeoutOrNull(30000) { peerCand.await() }
                if (peer == null) {
                    fail("timed out waiting for peer candidate")
                    return@launch
                }
                P2pBus.log("peer candidate = ${peer.ip}:${peer.port}, punching…")
                P2pBus.setStatus("PUNCHING")
                puncher.startPunch(scope, peer, ::onUdpEvent)
                val rtt = withTimeoutOrNull(60000) { punchDone.await() }
                if (rtt == null) {
                    fail("punch failed after 60s (asymmetric firewall or no v6 path)")
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
                if (!punchDone.isCompleted) punchDone.complete(e.rttMs)
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
                    regAck.complete(m)
                }
                "joined" -> {
                    code = m.optString("code")
                    P2pBus.log("joined room=$code yourAddr(ws)=${m.optString("yourAddr")}")
                    regAck.complete(m)
                }
                "ready" -> P2pBus.log("peer online: ${m.optString("peerInfo")}")
                "candidate" -> {
                    val ip = m.getString("ip").split("%")[0]
                    P2pBus.setStatus("GOT_PEER_CANDIDATE")
                    peerCand.complete(UdpPuncher.Cand(ip, m.getInt("port")))
                }
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
        if (!P2pBus.status.value.startsWith("CONNECTED")) P2pBus.setStatus("SIGNAL_DOWN")
    }

    private fun fail(note: String) {
        P2pBus.log("FAIL: $note")
        P2pBus.setStatus("FAILED")
        report(false, note)
    }

    private fun report(ok: Boolean, note: String) {
        runCatching {
            sig.send(
                JSONObject()
                    .put("type", "result")
                    .put("ok", ok)
                    .put("note", note)
                    .put("label", label)
                    .put("code", code ?: "")
            )
        }
    }

    fun sendChat(msg: String): Boolean =
        if (::puncher.isInitialized) puncher.sendText(msg) else false

    fun close() {
        if (::puncher.isInitialized) puncher.stop()
        if (::sig.isInitialized) sig.close()
    }

    companion object {
        val DEFAULT_STUNS = listOf(
            "stun.l.google.com" to 19302,
            "stun.cloudflare.com" to 3478
        )
    }
}
