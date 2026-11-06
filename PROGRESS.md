# Kuthay — Carrier Compatibility Research & Decisions

**Date:** 2026-11-05 (research v1; implementation landed same day — see §10)
**Question:** Does this repo work for all Indian internet carriers — Vi, Jio, Airtel? What causes failure, and can it be fixed?
**Short answer:** As shipped, it worked on *none* (two punch-blocking bugs made the UDP hole-punch impossible on every carrier). After the §10 implementation, native-IPv6 pairs work directly, and every impossible path (Vi/Airtel IPv4-only + symmetric CGNAT, NAT64, UDP-blocked, STUN-blocked) now automatically degrades to a **relayed chat over the signaling WebSocket** — Vi is covered by relay, not direct. True “works for all, direct P2P” is still not attainable by design: symmetric CGNAT requires a relay.

---

## 1. What the repo is (codebase map)

Kuthay is a 2-phone P2P chat prototype over Indian cellular, 3–4 km apart, with no consumer D2D radio (BLE/Wi-Fi Direct/PC5 sidelink all out of range — `docs/how-it-works.html` §1 is correct).

| Piece | Role |
|---|---|
| `app/.../UdpPuncher.kt` | IPv6-only `DatagramChannel`, external STUN probe, UDP PING/PONG punch, keepalive 350 ms → 2 s, TXT chat |
| `app/.../P2pSession.kt` | State machine: SIGNAL → STUN srflx → candidate exchange → punch (60 s) → CONNECTED |
| `app/.../SignalingClient.kt` | OkHttp WebSocket, 15 s ping |
| `app/.../P2pService.kt` | Foreground service (`dataSync`) to survive screen-off |
| `server/server.js` | Node + `ws` signaling :8080, room codes, candidate relay, result log; dead UDP probe :5688 |
| `docs/how-it-works.html` | The design doc — NAT/CGNAT/IPv6 explainer |

Design intent (docs §2–§4): **leverage India's world-leading native IPv6** to bypass CGNAT. If both phones have a globally routable IPv6 address, there is no NAT translation at all — only a stateful firewalls that opens for outbound traffic. Simultaneous UDP "knocks" from both sides create reciprocal firewall state and the hole is punched. This model is sound. The problems are in (a) the implementation, and (b) the carriers' real IPv6 coverage/fallback gaps.

---

## 2. Ground truth: Indian carrier IPv6, 2026

Source: APNIC Labs eyeball measurement, 2026-10-14 ([stats.ipv6.army/country/in](https://stats.ipv6.army/country/in/), [AS55836 Jio](https://stats.labs.apnic.net/ipv6/AS55836)).

| Carrier (ASN) | IPv6-capable | Prefers IPv6 | Samples |
|---|---|---|---|
| India overall | 76.6% | 74.2% | 123.5M |
| **Jio** (AS55836) | **96.2%** | 93.9% | 42.8M |
| **Airtel Mobility** (AS45609) | **89.5%** | 87.5% | 22.6M |
| **Vi** (AS38266) | **82.1%** | 76.1% | 0.26M |

BIF 2022 report (APNIC 30 Mar 2022): Jio 97.6/96.6%, Airtel 81.1/80.1%, Vi 79.1/76.2%, Idea 77.7/76.7% ([broadbandindiaforum.in PDF](https://broadbandindiaforum.in/wp-content/uploads/2022/08/Report-on-State-of-IPv6-in-India-online_14-JUNE-22.pdf)). Bhatia 2020: Jio 80%+, Airtel 45%, Vodafone+Idea ~50% where dual-stack is offered ([anuragbhatia.com](https://anuragbhatia.com/2020/02/networking/isp-column/indian-ipv6-deployment)); Bhatia 2026: "Jio and Airtel as high as 95% IPv6 preferred, Vi aggressively catching up" ([anuragbhatia.com](https://anuragbhatia.com/post/2026/06/ipv6-on-indian-govt-websites)).

**Reading:**
- India is world #1 for mobile IPv6 — the docs' premise is real. Jio launched IPv6-only from day one (LTE, 2016).
- But **~24% of Indian users are still IPv4-only** (BSNL 22.9%, most fixed broadband <50%). An IPv6-only app silently excludes ~1 in 4 users before any firewall is even tested.
- **Vi's measurement sample is 100× smaller than Jio's** — low-precision. Vi's subscriber base is shrinking and its 5G rollout is sparse, so its IPv6 tail (old APNs, 4G-only circles) is the largest of the three.

### 2.1 Jio — the best case and the NAT64 trap

- **IPv6-only access network + 464XLAT (RFC 6877):** UE gets a global /64 (e.g. `2405:201:*`, `2409:40d6:*`); IPv4 is synthesised locally by CLAT and translated back by PLAT NAT64/DNS64 in the core ([APNIC Jio IPv6-centric, apnic.net/50](https://conference.apnic.net/50/assets/files/APCS790/Harnessing-the-power-of-IPv6.pdf), [Bhatia Jio 5G IPv6-only](https://anuragbhatia.com/post/2023/02/jio-5g-ipv6-only/)). 5G/LTE both. 192.0.0.2/32 on PDP_IP0 is the IPv4 Service Continuity Prefix (RFC 7335), not a real IPv4 route.
- **CGNAT does not exist on the IPv6 path.** Your STUN srflx = your real SLAAC address. No NAT66 is in use by Jio.
- **Firewall:** stateful PGW filtering is the only gatekeeper. Simultaneous-open UDP in this category normally succeeds (EIM/APDM both allow reply traffic once outbound state exists).
- **The traps are NAT64-specific, and this app walks into both:**
  1. **DNS64 synthetic addresses.** If `stun.l.google.com` has no AAAA, `InetAddress.getAllByName()` on Jio can return a synthesised `64:ff9b::/96` AAAA. Kuthay treats it as a candidate and the peer (Airtel/Vi) cannot route `64:ff9b::/96` — deterministic asymmetric failure.
  2. **IPv4 signalling ≠ working data plane.** The WebSocket (TCP) happily rides NAT64 to an IPv4-only VPS hostname while UDP punching over NAT64-synthesised endpoints fails cross-carrier. Signalling connecting proves nothing about the punch.
  3. **IPv4 literals unreachable.** If the VPS signalling URL is a bare IPv4 literal (not a hostname), DNS64 synthesis cannot help — CLAT only works via DNS. Conversely an IPv6-literal VPS is unreachable from IPv4-only peers.
- **Net:** Jio↔Jio native-IPv6 is the single easiest pair in India for this design. Jio→Airtel/Vi over native IPv6 is also strong *provided* the app never advertises a NAT64-synthesised IPv4 candidate.

### 2.2 Airtel — dual-stack, IPv6 is real, IPv4 is symmetric CGNAT

- **Dual-stack (IPv4/IPv6 PDN), not IPv6-only.** UE gets a CGNAT IPv4 (/32 in `100.64.0.0/10`, RFC 6598) *and* a global IPv6 /64 (`2404:a800::/32`, `2405:a700::/48`, …). No NAT66.
- **APN:** `airtelgprs.com`, **APN Protocol + Roaming Protocol must be `IPv4/IPv6`** — the #1 user-side failure is a stale APN stuck on `IPv4` ([speed.is Airtel APN](https://speed.is/in/airtel/apn), HN thread on Airtel APN). Verify at test-ipv6.com / SIM status shows `2404:`/`2405:`.
- **IPv4 CGNAT is symmetric (APDM):** documented since 2014 (UE `100.92.215.253` → shared public egress) and an India Broadband Forum teardown found the CGN behaves as *full-cone against STUN on 3478 but symmetric on non-standard ports (e.g. 10000)*. Kuthay's probe pair (`stun.l.google.com:19302` first, `stun.cloudflare.com:3478` second) will therefore **disagree with each other on Airtel IPv4** — and the 19302 answer is the honest one. Plain STUN hole-punching over Airtel IPv4 should be assumed to fail; this matches Tailscale's 2026 field note that Indian ISPs "commonly use symmetric NAT (Endpoint-Dependent Mapping), which breaks standard UDP hole punching" ([tailscale.com/blog](https://tailscale.com/blog/peer-relays-international-networks)).
- **IPv6 firewall:** no NAT, only a stateful inbound filter (implementation notes: it can be endpoint-independent *or* address-and-port-dependent depending on PGW config — [Nokia SR docs](https://documentation.nokia.com/sr/26-7/7750-sr/books/ms-isa-esa/ipv6-stateful-inbound-filtering.html)). Both styles allow simultaneous-open UDP; unsolicited inbound dies. The exact mobile PGW mode is not publicly disclosed and must not be assumed — it must be probed.
- **Do not conflate Airtel mobile with Airtel Xstream fibre** — fibre IPv6 is frequently absent/broken and governed by the ONT firewall, a different product.
- **Net:** Airtel↔Airtel over native IPv6 = high success (cleanest dual-stack case). Airtel↔Jio over native IPv6 = high, arguably best. Airtel's own IPv4-only tail (~10%) is fatal for this app as-is.

### 2.3 Vi (Vodafone Idea) — dual-stack on paper, most variable in practice

- **Dual-stack APN** `www`/`internet`, protocol `IPv4/IPv6` ([internetsettings.org Vi APN](https://www.internetsettings.org/en/india/vi-apn-settings)). No Vi NAT64/IPv6-only deployment; Vi is an IPv4-primary network that added IPv6.
- **Coverage:** APNIC 82.1% capable today, but from ~50% in 2020 ([Bhatia 2020](https://anuragbhatia.com/2020/02/networking/isp-column/indian-ipv6-deployment)) and Vi's 5G is limited (4G-centric, refarmed spectrum), so users on old APN profiles, 3G-era SIMs, or rural circles are disproportionately IPv4-only.
- **IPv4 CGNAT:** same 100.64/10 symmetric CGNAT as the other two carriers; no public evidence of Vi *blocking* STUN/UDP specifically, but as with Airtel/Jio, expect per-destination port mapping — STUN srflx ≠ peer port over IPv4.
- **Net:** Vi↔Vi over native IPv6 works by the same mechanism as Airtel↔Airtel, but the *probability both sides actually have IPv6* is lowest. Vi is where the "IPv4-only tail" bites hardest, especially Vi→Jio (Jio has no IPv4 data plane to fall back to).

### 2.4 Pairwise matrix (assuming the P0 code bugs in §3 are fixed; native IPv6 both sides, APN = IPv4/IPv6, no VPN)

| Pair | Expected direct success | Why |
|---|---|---|
| Jio ↔ Jio | **Very high** | Both global IPv6, no CGNAT in path. Best case in India. |
| Jio ↔ Airtel | **High** | Native v6↔v6 routes normally; fails only if app advertises Jio's NAT64 IPv4 candidate or Airtel side lacks IPv6. |
| Airtel ↔ Airtel | **High** | No NAT on v6 path, only stateful firewall pinhole. |
| Jio/Airtel ↔ Vi | **Medium** | Mechanism identical; risk is the *Vi side* being IPv4-only. Non-deterministic. |
| Vi ↔ Vi | **Medium** | Lowest joint probability both sides have IPv6. |
| Any pair over IPv4 | **Low — assume relay** | Symmetric CGNAT (APDM): STUN port ≠ peer port on all three carriers ([natcheck](https://1mb-dev.github.io/natcheck/nat-types/), [WebRTC over CGNAT](https://www.real-time-media-architecture.com/webrtc-protocol-stack-signaling-servers/ice-candidate-gathering-filtering/webrtc-over-cgnat-carrier-grade-nat/)). |
| Any pair, one side Wi-Fi (home/enterprise NAT) | **Low** | No host candidates in app; srflx = router's public IP, not the phone's. |

Universal references: Tailscale's model treats IPv6 as "Easy NAT" (direct in almost every situation) and symmetric CGNAT as "Hard NAT" (always relay when paired with Hard/Easy) ([tailscale device-connectivity](https://tailscale.com/docs/reference/device-connectivity.md)); WebRTC/ICE doctrine is the same — STUN alone suffices only for EIM/cone NAT, symmetric/CGNAT requires TURN ([natcheck](https://1mb-dev.github.io/natcheck/nat-types/)).

---

## 3. Why it does not work today — even on ideal Jio

Code audit (all files read; subagent explore + manual verification):

### 3.1 P0 blocker #1: `sessionId` mismatch — punch can *never* succeed

- `P2pSession.kt:18` generates a random 8-char `sessionId` per device; `P2pSession.kt:43` (`UdpPuncher(sessionId)`) uses that device's own ID for outbound payloads (`UdpPuncher.kt:122-123`: `K|$sessionId|PING|…`).
- `UdpPuncher.kt:143` drops any packet not starting with `K|$sessionId|` using the **receiver's own** ID.
- Signalling never exchanges the ID: register/join sends only code/label/device (`P2pSession.kt:31-37`), `candidate` sends only `ip`/`port` (`P2pSession.kt:64-66`), server forwards only `ip`/`port` (`server.js:69-76`).

**Result:** every PING is dropped at the first line of `handlePacket` on the peer. The app always ends at `punch failed after 60s (asymmetric firewall or no v6 path)` (`P2pSession.kt:77-79`) — a misdiagnosis of a protocol bug. **Carrier-independent, 100% reproduction.**

### 3.2 P0 blocker #2: IPv6 STUN de-XOR is wrong — wrong address is punched

- Request construction (`UdpPuncher.kt:55-61`, type `0x0001`, magic `0x2112A444` ← wrong, correct is `0x2112A442` — discovered only by the 2026-11-06 external review; see §12, unit tests never caught it), 12-byte txid). Header validation rejects non-`0x2112A442` cookies in real servers by definition. Port de-XOR (`:98-99`) is correct.
- IPv6 address de-XOR (`:100-105`) XORs the 16-byte address only with the 4 magic bytes repeated (`MAGIC_BYTES[j % 4]`), where RFC 5389 §15.2 requires XOR with the full 16-byte mask = **magic cookie + transaction ID**. Every IPv6 srflx is therefore a garbled address (unless txid coincidentally equals magic-repeated). The `:ffff:` guard (`:106`) does not detect this, so a wrong candidate is returned and punched for 60 s.
- The log `my srflx = <garbled>:<port>` looks plausible — field tests will blame the carrier firewall for an app bug.

### 3.3 Carrier-dependent by design (and by omission)

1. **IPv6-only bind.** `DatagramChannel.open(INET6)` + `bind(::)` (`UdpPuncher.kt:35-38`). Any IPv4 APN, IPv4-only Wi-Fi, IPv4-only circle, or kernel IPv6 disabled → exception at bind (caught as opaque `session error`, `P2pSession.kt:84-86`) or `Network unreachable` at send (misreported as "UDP 19302/3478 blocked?"). Family-1 (IPv4) STUN answers are discarded via `sawV4Only` (`UdpPuncher.kt:107-109`).
2. **One srflx candidate, no ICE.** `peerCand: CompletableDeferred<Cand>` (`P2pSession.kt:23`) and `Cand(ip,port)` (`UdpPuncher.kt:16`) model exactly one candidate per peer. No host candidates (same-Wi-Fi peers can never connect), no candidate list, no pair testing, no port prediction for symmetric NAT, no PCP/UPnP.
3. **No relay (TURN) — by design zero.** Docs acknowledge the cost (`how-it-works.html` §2, §8) but the code ends at `FAILED` after 60 s with no fallback or "needs-relay" signal. This is the correct explanation for **Vi/Airtel IPv4-only and symmetric-CGNAT users**: there is no path at all, not a slow one.
4. **Two hard-coded external STUN servers, no configurability.** `stun.l.google.com:19302` (nonstandard, tried first) then `stun.cloudflare.com:3478` (`P2pSession.kt:162-167`). No self-hosted/custom STUN in the UI. Sequential probe: `UdpPuncher.kt:43-52` ×3 attempts ×1.5 s + 0.3 s ≈ 21–25 s worst case, colliding with the 25 s timeout (`P2pSession.kt:55`). Carrier/firewall policies that only permit standard 3478 burn the budget on 19302 first.
5. **The VPS UDP probe is dead code and non-STUN anyway.** `server.js:100-118` (`udp6` :5688) expects JSON `{"kuthay":true,"sid":…}` and replies `{"ip","port"}` — not STUN, so `UdpPuncher` (expects `0x0101` response) would reject it. The Android client never references 5688. Docs (§5–§7) describe this probe as *the* srflx path; the shipped code uses Google/Cloudflare. It provides zero fallback and would also fail silently on an IPv4-only VPS.
6. **NAT64 mishandling (Jio).** See §2.1: DNS64-synthesised `64:ff9b::/96` treated as a real candidate; STUN over NAT64 returning Family-1 sets `sawV4Only` and emits a false "NO IPv6" error even though the handset has native IPv6. No detection/suppression of CLAT-synthesis addresses.
7. **Split-brain signalling.** `SignalingClient` (OkHttp WebSocket, dual-stack) connects over IPv4/NAT64/Wi-Fi IPv4 where UDP can never work. `yourAddr(ws)` is logged (`P2pSession.kt:106,112`) but never gated (`how-it-works.html` §8 knows `::ffff:49.36.x.x` means no-IPv6 — the client ignores it). One peer can be on IPv4, the other on IPv6, and candidate exchange proceeds partially until a 30 s/60 s timeout.
8. **No network awareness.** `ACCESS_NETWORK_STATE` is declared but `ConnectivityManager`/`NetworkCallback`/`NetworkInterface` are never used (zero grep matches). Dual-SIM secondary subscription, Wi-Fi↔cell handover, LTE→5G cell reselection, SLAAC privacy-address rotation and prefix change (which invalidates the punched 5-tuple) all break the session with no re-probe. `regAck`/`peerCand`/`punchDone` are one-shot `CompletableDeferred`s that are never reset.
9. **Doze/keepalive gap.** Keepalive is generous (350 ms → 2 s, `UdpPuncher.kt:120-128`) but there is no `PARTIAL_WAKE_LOCK`, `WifiLock`, or battery-optimisation exemption prompt — foreground service (`P2pService.kt:22-35`) alone does not guarantee CPU/radio wake on Android 12–15 (targetSdk 35). TXT sends are fire-and-forget with no seq/ack (`:165-168`); silent keepalive loss is indistinguishable from NAT expiry. 1500-byte buffer (`:131`) with no MTU control risks IPv6 fragmentation drops.
10. **Robustness bugs that mimic carrier failure:**
    - Non-blocking `ch.send()` return value ignored (`:63`, `:173`) — a return of 0 treats "not queued" as sent; looks like random carrier loss.
    - `handlePacket` does `p[3].toLong()` (`:157`) with no try/catch in the receive coroutine (`:129-139`) — a malformed/spoofed PONG throws `NumberFormatException`, kills the receiver launch, and the session silently times out ("no v6 path").
    - `parseStunResponse` can throw `BufferUnderflowException` on malformed attributes (only the send is try-wrapped); a truncated attribute silently returns null → "STUN unreachable".
    - No peer validation: any source matching the session-ID prefix can spoof PING/PONG/TXT and trigger PONG replies to `from` (`:142-163` never checks `from` against `peer`).
    - `peerCand.complete(...)` (`P2pSession.kt:116-120`) without `isCompleted` checks → `IllegalStateException` on a retransmitted candidate.
    - `P2pService.startTest` (`:38-49`) leaks the previous session (sockets/WebSockets) on every start intent; `SignalingClient.close()` (`:47-53`) races `P2pSession.report()` (`:141-152`); `P2pSession.close()` (`:157-160`) calls `puncher.stop()` on a `lateinit` channel after a bind failure → crash.
    - Session IDs are 32 bits (`UUID…take(8)`) and candidates carry no session ID, so a stale candidate punches a wrong peer for 60 s.

### 3.4 What this means per carrier

| Carrier | As shipped | After P0 fixes (§4) |
|---|---|---|
| Jio (96.2% v6) | **Fails** (sessionId + STUN bugs) | **Works** for the ~94% who prefer IPv6, incl. Jio↔Jio |
| Airtel (89.5% v6) | **Fails** (same bugs) | **Works** for the IPv6 subset; IPv4-only tail fails (symmetric CGNAT, no relay) |
| Vi (82.1% v6) | **Fails** (same bugs) | **Works** for IPv6 subset; largest failure share (IPv4-only + symmetric CGNAT) |

---

## 4. Can it be fixed? — Yes, in three layers

### P0 — make Jio↔Jio work at all (small, targeted)

1. **Exchange or drop the session-ID gate.** Either include `sessionId` in signalling (`candidate` payload + server forward) or validate `from == peer` instead of prefix-matching the receiver's own ID. (Fixes §3.1.)
2. **Fix IPv6 de-XOR** to use the 16-byte mask (magic cookie + txid) per RFC 5389 §15.2. (Fixes §3.2.)
3. **Gate on `yourAddr`:** if it is `::ffff:`-mapped (IPv4 fallback) or the srflx is IPv4-only, fail fast with a *correct* diagnosis and APN guidance (per-carrier table in §5), instead of burning 60 s in punch.
4. **Robustness:** check `ch.send()` return; try/catch around `handlePacket`/`toLong()` and `parseStunResponse` buffer reads; `isCompleted` guards before `CompletableDeferred.complete`; guard `puncher.stop()` on uninitialised channel; validate `from` against `peer`.

### P1 — carrier-robust direct path (moderate)

5. **STUN hardening:** try standard 3478 before 19302; add a self-hosted STUN on the VPS (coturn in STUN-only mode, or fix the probe to speak real STUN and actually use it); support TCP/TLS STUN (5349) for UDP-hostile networks; respect ERROR-CODE attributes; parallel (not sequential) probes with Happy-Eyeballs-style racing.
6. **NAT64 awareness (Jio):** detect and deprioritise/suppress `64:ff9b::/96` synthesised candidates; never advertise an IPv4/CLAT candidate to a peer when you have a native IPv6 host candidate; prefer IPv6 literals; resolve STUN via AAAA only on IPv6-only networks. Use hostnames (not IPv4 literals) for the signalling URL so DNS64 synthesis can carry the WebSocket.
7. **Dual-stack socket + IPv4 path:** dual `DatagramSocket` (or two channels, INET6 + INET) with *parallel* IPv4 + IPv6 candidate gathering; accept Family-1 STUN as a genuine candidate (diagnose symmetric NAT by comparing two STUN servers' mapped ports — `MappingVariesByDestIP`); host candidates from `NetworkInterface` for same-Wi-Fi peers.
8. **Network-change handling:** register `ConnectivityManager.NetworkCallback`; on default-network change / prefix change, re-bind, re-probe, re-signal candidates. Add `PARTIAL_WAKE_LOCK` + battery-exemption prompt.

### P2 — "works for all carriers" (architecture change)

9. **Relay fallback is non-negotiable for IPv4 symmetric CGNAT.** Options, in increasing effort:
   - **(a) Lowest effort, immediate:** if punch fails after N seconds, fall back to relaying TXT over the existing WebSocket signalling channel (server forwards chat frames). Guarantees Vi/Airtel IPv4-only users at least get chat, at the cost of server bandwidth. This is the DERP play from Tailscale.
   - **(b) Proper:** self-hosted coturn (TURN/UDP 3478 + TURN/TLS 5349/443) as the relay path; ICE-style candidate ordering (host → srflx → relay) with direct tried first. TURN over TCP/TLS on 443 is also the escape hatch for UDP-blocked networks.
   - **(c) Advanced:** peer-relay (a connected phone relays) as in Tailscale peer relays — useful where the VPS is expensive but a third phone sits on good connectivity.
10. **Port prediction / PCP / UPnP:** possible for some symmetric NATs but unreliable against CGNAT (the port is per-destination by construction). Not recommended as the primary fix — relay is.
11. **Do not promise "works for all" on direct P2P.** The honest claim after P0+P1 is "direct over native IPv6 where both sides have it (≈ Jio 94%, Airtel 88%, Vi 76% preferred); relayed elsewhere". Marketing it as universal direct P2P will be disproven by the first Vi IPv4-only test.

**Estimate:** P0 is a one-day fix. P1 is a week. P2(a) is another few days (server-side forwarding + client fallback trigger); P2(b) is infra + a day of client work. No carrier policy change is required — none of this depends on Jio/Airtel/Vi doing anything differently, except P0/P1 requiring users to have APN Protocol = IPv4/IPv6 (which is already the carrier default on current carrier profiles; stale profiles are the exception and the app should diagnose them).

---

## 5. User-side carrier checklist (diagnosis when test fails)

| Carrier | APN | Protocol | Verify IPv6 | Common failure |
|---|---|---|---|---|
| Jio | `jionet` (auto) | IPv4/IPv6 (auto) | SIM status shows `2405:`/`2409:`; test-ipv6.com | None usually; if STUN returns a `64:ff9b::` address, app bug (NAT64 synth) |
| Airtel | `airtelgprs.com` | **IPv4/IPv6** (incl. roaming) | Same as above | APN stuck on IPv4 → log shows `::ffff:` `yourAddr` → set IPv4/IPv6, airplane-mode toggle, reboot |
| Vi | `www` / `internet` | **IPv4/IPv6** (incl. roaming) | Same as above | IPv4-only APN/circle → same fix; if still no IPv6, this SIM/circle is IPv4-only and needs the relay path (P2) |

All three: kill VPNs / ad-blocker "WebRTC leak protection", disable battery optimisation for the app, keep foreground service running, test on mobile data (not home Wi-Fi — a different NAT problem).

---

## 6. Decisions taken

| # | Decision | Rationale |
|---|---|---|
| D1 | **IPv6-first remains the core bet.** India is the best place on Earth for this (76.6% capable, Jio 96.2%). | APNIC 2026 data; Tailscale/WebRTC doctrine (IPv6 = Easy NAT). |
| D2 | **P0 bugs (§3.1, §3.2) are fixed before any carrier claim or field test.** Testing carriers while the punch is impossible by construction wastes SIM time and produces garbage data. | Both bugs are 100% reproduction, carrier-independent. |
| D3 | **Add a relay fallback (P2a signalling relay first, TURN next) and change the product claim** from "direct P2P for all carriers" to "direct on native IPv6, relayed elsewhere". | Symmetric CGNAT on IPv4 is mathematically unbeatable by hole-punching ([natcheck](https://1mb-dev.github.io/natcheck/nat-types/)); ~24% of Indian users are IPv4-only. Only a relay covers them. |
| D4 | **Self-host STUN (and TURN) on the VPS; demote Google 19302 to last resort.** | Dead probe code shows self-hosting was intended; 19302 (nonstandard) is first today and disagrees with 3478 on Airtel's CGN; external STUN is a SPOF and a data point for carrier middleboxes to throttle. Standard port 3478 first. |
| D5 | **Detect/suppress NAT64-synthesised candidates (`64:ff9b::/96`) and never advertise CLAT IPv4 candidates when native IPv6 exists.** | Jio's 464XLAT makes these the most likely silent cross-carrier failure after P0. |
| D6 | **Field validation plan:** matrix of 3×3 carrier pairs × (mobile data) with 2 phones each, plus Wi-Fi and IPv4-APN negative controls; instrument with `stunner` / `tailscale netcheck` (`MappingVariesByDestIP`), `yourAddr` family, srflx family, and punch-half-open events already in the log. Segment Airtel mobile vs fibre results. | Carrier firewall modes are undisclosed; must be probed, not assumed. The app's own state machine already logs the stage that broke. |
| D7 | **Docs (`how-it-works.html`) to be corrected** to match shipped code (dead probe, session-ID exchange, STUN mask) once P0 lands — current docs describe unshipped behaviour. | Prevents the next field test from being designed against a fiction. |

---

## 7. Open unknowns (need live SIMs to close)

- Exact PGW firewall filtering style (EIM vs APDM) and UDP timeouts per carrier — not publicly disclosed for any of the three.
- CGNAT port-allocation scheme (sequential vs randomised vs block) — operator-internal; only matters for IPv4 port prediction, which P2 makes unnecessary.
- True Vi IPv6 share in specific circles (APNIC sample too small to segment; BIF 2022 says 79% capable but that was 4 years ago and 5G has since changed things).
- Whether Vi/Airtel throttle or drop UDP to non-standard ports beyond DNS/QUIC/VoLTE — no carrier-specific evidence found; P1's multi-port STUN + TURN/443 covers it either way.

## 8. Sources

- APNIC Labs IPv6 stats (India, Jio, Airtel, Vi ASNs): stats.labs.apnic.net/ipv6/{IN,AS55836,AS45609,AS38266}, stats.ipv6.army/country/in (2026-10-14)
- Jio IPv6-only/464XLAT: conference.apnic.net/50 (Jio IPv6-centric), anuragbhatia.com/post/2023/02/jio-5g-ipv6-only, APNIC blog ipv6-only vs ipv6-mostly (2026-07-07)
- Indian IPv6 history & per-carrier percentages: anuragbhatia.com (2020, 2026 posts), broadbandindiaforum.in State-of-IPv6 PDF (2022)
- Airtel APN/CGNAT symmetric behaviour: speed.is Airtel APN, broadband.forum Airtel CGNAT thread (3478 full-cone vs non-standard symmetric)
- Vi APN: internetsettings.org Vi APN, apnsettingsandroid.com Vi 4G
- NAT traversal doctrine: 1mb-dev.github.io/natcheck/nat-types, real-time-media-architecture.com WebRTC-over-CGNAT, tailscale.com (device-connectivity, peer-relays/DERP, NAT-traversal blog)
- Carrier firewalls / IPv6 stateful filtering: Nokia SR IPv6 stateful inbound filtering docs, Telenor IPv6 mobile template (generic PGW behaviour)

---

## 9. Decision log (addendum — implementation, same day)

| # | Decision | Rationale |
|---|---|---|
| D8 | **Session id is exchanged over signaling** (`sid` in candidate/result frames, server relays it) and the receiver filters by `from == peer` **and** the peer’s `K\|peerSid\|` prefix — belt and braces. Closes §3.1. | Prefix check alone can’t pass since each device generates its own id; exact-peer-address check is strictly safer. |
| D9 | **Session id is NOT guessable / not validated beyond addressing**; if a peer never learns our sid (old client), the `sid` check degrades to accepting any `K\|` from the peer address only. Documented behaviour. | Robust mixed-version behaviour; security is explicitly out of scope for the prototype’s threat model. |
| D10 | **Fixed STUN de-XOR** to use the 16-byte `cookie++txid` mask per RFC 5389 §15.2; IPv6 srflx now decodes correctly. NAT64 `64:ff9b::/96` candidates are dropped (falls back to relay). Family-1 answers set `sawV4Only` and produce an APN-correct diagnosis instead of “UDP blocked”. | Closes §3.2 and the NAT64 class of silent failure. |
| D11 | **Dead UDP probe (`:5688`) deleted**, not fixed — it was non-STUN and unreferenced. STUN servers reordered: Cloudflare :3478 first (Airtel CGN treats non-standard 19302 worse), Google :19302 second. NAT64-deprioritised DNS64 AAAA endpoints are skipped when a native v6 endpoint exists. | D4 posture: standard ports first, fewer spurious failures. |
| D12 | **Relay fallback implemented as P2a (signalling-channel relay), not TURN (P2b).** When the direct path is impossible (socket unavailable / STUN IPv4-only / NAT64 srflx / peer timeout / symmetric CGNAT / punch timeout after 20 s), the app enters `RELAYED` and chat rides the WebSocket via the new `relay-request`/`relay-msg` frames forwarded by the server. Both peers flip together; no request ping-pong. | Guaranteeing Vi coverage mattered more than TURN’s bandwidth economy; needs no coturn deployment to ship; D3 satisfied. |
| D13 | **`startPunch` shortens the punch window 60 s → 20 s** for faster fall-back to relay; connect button stays enabled in `RELAYED`. | Field-test UX; symmetric CGNAT is unbeatable by punching anyway. |
| D14 | **Session hygiene fixes:** `CompletableDeferred.complete` guarded by `isCompleted`; previous sessions closed on restart/destroy; `PARTIAL_WAKE_LOCK` added (AndroidManifest `WAKE_LOCK` + service acquire/release); `puncher.stop()` no longer crashes pre-initialised channels; IPv6 channel `receive()` wrapped in try/catch; PONG `toLongOrNull` guarded. | Closes the “robustness bugs that mimic carrier failure” cluster in §3.3-10. |
| D15 | **`yourAddr(ws)` is now honoured** — logged as IPv4-vs-IPv6 with an explicit note that an IPv4-mapped leg can’t punch; NAT64 srflx rejected pre-punch. Split-brain is surfacing diagnostics now, yet signalling success ≠ data-plane success is still never advertised as more than it is (see still-open items). | §3.3-7 mitigation, honest logging without over-claiming. |
| D16 | **Docs corrected to match shipped code** (probe removed, relay fallback, sid exchange, 20 s window, failure semantics). CI unchanged (`.github/workflows` still only builds the debug APK). | D7 completed. |
| D17 | **STUN magic cookie corrected to RFC 5389 §6 (`0x2112A442`)** — original repo shipped `0x2112A444`; inherited into my unit-test mocks. Verified live against `stun.l.google.com:19302` and `stun.cloudflare.com:3478`: wrong cookie → Google legacy-REPLY / Cloudflare timeout; correct cookie → both return XOR-MAPPED-ADDRESS. | Bug class invisible to unit tests; needed live verification (see §12). |
| D18 | **Server buffers the latest candidate per side** (`room.hostCand`, `room.joinerCand`) and flushes to a late joiner — host candidates routinely precede any joiner, and were silently dropped before. | Eliminates the guaranteed 30 s timeout / forced relay on Jio↔Airtel for a pure race condition, not carrier policy. |
| D19 | **Repo hygiene:** `node_modules` untracked (kept on disk, `.gitignore`d), `./gradlew` generated (Gradle 8.9). | CI needed these; .gitignore alone doesn't untrack committed files. |

## 10. Implementation status — 2026-11-05

### What changed (files)
- `app/src/main/java/com/kuthay/p2p/UdpPuncher.kt` — P0 STUN fix (§3.2), NAT64/`:ffff:` rejection, peer-address + peer-sid filtering, safe packet parsing, `ch.close()` on null-safe channel, full-capacity receive buffer handling.
- `app/src/main/java/com/kuthay/p2p/P2pSession.kt` — session-id exchange (P0 §3.1), NAT64/mapped diagnostics, STUN reorder + native-only AAAA preference, 20 s punch window, `RELAYED` fallback with `relay-request`/`relay-msg`, split-brain logging, robust deferred completion.
- `app/src/main/java/com/kuthay/p2p/P2pService.kt` — `PARTIAL_WAKE_LOCK` acquire/release; closes any prior session on restart; `onDestroy` closes session + releases lock.
- `app/src/main/java/com/kuthay/p2p/UdpPuncher.kt` — also `isNat64` / `isV4Mapped` helpers used by both files.
- `app/build.gradle.kts` — `testImplementation("junit:junit:4.13.2")` only.
- `app/src/main/AndroidManifest.xml` — `WAKE_LOCK` permission.
- `server/server.js` — dead VPS probe removed; `sid` passthrough on register/join/candidate; new `relay-request` / `relay-msg` forwarding; `KILL` messages to peer on disconnect unchanged.
- `docs/how-it-works.html` — corrected (§5/§6/§7/§8, punch window 60→20 s, RELAYED state + chip).
- `.gitignore` — new (Android/Gradle outputs; `.gradle`, `build`, `local.properties`, `.DS_Store`).
- `app/src/test/java/com/kuthay/p2p/PunchTest.kt` — new JVM unit tests **against the real shipped classes** (run with `:app:testDebugUnitTest`).

### Verification actually performed (2026-11-05)
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL**, `player: app/build/outputs/apk/debug/app-debug.apk` (≈2.65 MB) via Homebrew OpenJDK 17 + cached Gradle 8.7 (this machine’s `gradle`/wrapper was absent — see note under “environment”).
- `gradle :app:testDebugUnitTest` → **9/9 tests pass**: IPv6 XOR-MAPPED de-XOR with full cookie+txid mask; IPv4-only flag path; malformed attribute resilience; odd-attribute padding skip/clamp; NAT64 prefix detection (compressed + uncompressed forms); peer/sid filtering (drop non-peer source, drop wrong sid, accept peer); ADM source adoption; strict source pinning after CONNECTED; loopback PING frame sanity.
- Node end-to-end relay test (`server/server.js` + `ws` client): register → join → ready, candidate relay **with sid passthrough**, `relay-request` delivered to peer, `relay-msg` delivered to peer, peer-disconnect notification — **all assertions pass**. Syntax checked with `node --check`.
- **No carrier SIMs were used.** Cellular-behaviour claims in §2 rest on published measurements; Wi-Fi and 3×3 carrier matrix still must be field-tested on real devices (D6).

### Per-carrier expectation after the fix
| Pair | Direct (IPv6) | If impossible |
|---|---|---|
| Jio ↔ Jio | ✅ should work (~94%) | RELAYED |
| Jio ↔ Airtel | ✅ usually | RELAYED |
| Jio/Airtel ↔ Vi | ⚠️ Vi-dependent | RELAYED (guaranteed chat) |
| Vi ↔ Vi | ⚠️ lowest | RELAYED |
| Anything IPv4-only | ❌ symmetric CGNAT | RELAYED |
| Same Wi-Fi both | ❌ (no host candidates) | RELAYED |

### Still open (explicit, honest)
1. **Network-change handling** — `ConnectivityManager.NetworkCallback` / re-probe / re-signal on cell reselection or prefix change (P1 item §4-8) NOT implemented. The session will stall rather than recover on network switch.
2. **Real TURN infra** — coturn (UDP 3478 + TLS 5349/443) with credentials, KDF from VPS, ICE-style candidate ordering. P2a (signaling relay) carries chat now, consuming ws bandwidth; TURN adds the “direct-but-via-relay datacentre” path for high-bandwidth needs later.
3. **Battery-optimisation exemption prompt** not added (Play policy + UX), `PARTIAL_WAKE_LOCK` is the only mitigation; doze can still bite.
4. **No host candidates / ICE / PCP / port prediction** — same-Wi-Fi and partial-NAT cases all degrade to relay; that is intentional, relay is the honest answer there.
5. **Encrypted signalling** — `ws://` cleartext unchanged (needs cert + `wss://`, plus basic auth; silently relaying text over plaintext is not fine for private messaging).
6. **No authentication / authorisation** on rooms or relay; room codes are 20-bit entropy and enumerable by design.
7. **Docs still describe Google IPv6 stats from the old figure** except where corrected above; deeper carrier validation matrix, battery, NAT-timeout measurements untested.

### Environment note (build)
The repo was missing `gradlew` and its `java` on PATH was broken (macOS stub). Fixed: `./gradlew` + `gradle/wrapper/gradle-wrapper.jar` generated (Gradle 8.9, matching CI `.github/workflows` which uses JDK 17 + Gradle 8.9 via `gradle :app:assembleDebug`). Local verification here used Homebrew OpenJDK 17 (`/opt/homebrew/opt/openjdk@17`); `./gradlew --version` works and resolves Gradle 8.9.

*(Original v1 conclusion “next step: implement P0 then re-run the 3×3 matrix” is now: field-test §10 matrix on two phones per pair; collect logs; only then decide whether TURN/TLS is worth the VPS cost.)*

## 11. Post-review fixes (same-day self-audit, after first §10 implementation)

After §10 landed, the implementation was re-reviewed as a whole (`git diff`). The following were **wrong in our own code and are now fixed**:

1. **ADM/APDM hole-punch was impossible because of our own `from == peer` filter.** Address-dependent CGNs (the dominant pattern on Indian carriers per natcheck/Tailscale) re-roll the peer's source port *per destination*, so the peer's punch arrives from a different port than its STUN candidate — our filter silently dropped every such packet, turning a class of pairs that *can* connect via simultaneous-open into relayed ones. Fix: while punching, a packet that passes the sid check and shares the peer's IP **adopts the observed source** (ICE behaviour). After `CONNECTED`, port matching is strict again (spoof redirection closed). Tests added: ADM adoption + post-connect strictness. 9/9 pass.
2. **Punch storm was never stopped on relay entry.** After a failed punch, `startPunch`'s loop would keep blasting PINGs forever on a path we had already given up on. Fix: `enterRelay()` calls `puncher.stop()`.
3. **Late `GOT_PEER_CANDIDATE` could overwrite `RELAYED`** in the log, disabling the send button in a state where relay works fine. Fix: candidate frame no longer overwrites status once relayed.
4. **STUN-DNS outage now converges symmetric** — `cannot resolve any STUN server` also enters relay instead of sitting in FAILED until the peer times out.
5. **Server `relay-msg` uncapped** — unbounded frame forwarding. Fix: `maxPayload: 64 KB` `WebSocketServer` option + `relay-msg` text sliced to 4096 chars.
6. **STUN attribute padding could overshoot the buffer** (odd-length attrs followed by padding) — replaced `position(start + padded)` with a clamp to `limit`.
7. Test suite caught a regression **we introduced while fixing #1** — the first adoption implementation let frames from *wrong IPs with a valid sid* pass through. Tightened to same-IP adoption only. Lesson baked back into the suite (all 9 tests green).

**Honest residual notes from the review:**
- The `from == peer` pinning means: if **both** NATs are endpoint-dependent (ADM×ADM), direct still fails — by NAT semantics, not by our code — and relay engages (that is the correct fallback per natcheck doctrine).
- Adoption only happens pre-`CONNECTED` and within the same IP CIDR-sane check; it does not elevate trust in the frame beyond the sid.
- Adoption applies to any `K|` frame passing the sid check (PING/PONG/TXT) pre-connect — that is intentional, since PINGs are what needs to pass. What it does *not* do: authenticate frame contents, or authenticate anything beyond the sid. After `CONNECTED` the source is pinned exactly and no adoption occurs.

## 12. External review — claims verified and fixed (same day)

An outside reviewer re-checked the §9–§11 work and reported two blockers + hygiene items. Every claim was **verified independently before acting**, fix applied, and end-to-end tested:

**Claim 1 — STUN magic cookie typo (TRUE, worst of the set; introduced by the original repo, carried into my test mocks).**
- RFC 5389 §6 (verified against `rfc-editor.org/rfc/rfc5389#section-6`): *"The magic cookie field MUST contain the fixed value `0x2112A442` in network byte order."* The code had `0x2112A444` since the original author; I propagated it into both the production constant and the PunchTest mocks (which is why the unit tests "passed" while the protocol constant was wrong).
- Impact chain: request bytes didn't match any RFC 5389 server → Google demoted the message to legacy RFC 3489 mode (returns `MAPPED-ADDRESS` `0x0001`, **not** `XOR-MAPPED-ADDRESS` `0x0020`, which the parser requires); Cloudflare silently dropped/ timed out. App would log "STUN unreachable" → always `RELAYED`. Reported pattern exactly reproduced live on this machine (see below).
- Fix: cookie → `0x2112A442`; `MAGIC_BYTES` last byte → `0x42`; all test literals → `0x42`.
- **Empirical proof after fix** (live UDP probe, this machine, real servers):
  - before / wrong cookie (`0x2112A444`): `stun.l.google.com:19302` → answered with legacy `MAPPED-ADDRESS=152.58.16.170` only; `stun.cloudflare.com:3478` → **timeout**
  - after / correct cookie (`0x2112A442`): both servers answer with correct `XOR-MAPPED-ADDRESS` (`152.58.16.170:62065`, `:51453` respectively) — the parser now succeeds.
- Lesson recorded: unit tests alone can't catch a constant typo shared by code and mock; one live probe check would have. Any future STUN change should include a live smoke check against a public server.

**Claim 2 — server candidate race (TRUE; direct reproducible failure, not theoretical).**
- Reality: host registers → STUN probes (~1–25 s) → sends `candidate` while joiner hasn't connected yet → `room.joiner` undefined → `send(undefined)` silently no-ops → joiner later joins with no host candidate → joiner waits 30 s → `RELAYED` even on two perfect IPv6 networks. 
- Fix: candidates are buffered per side (`room.hostCand`, `room.joinerCand`); the joiner flush occurs right after `joined`/`ready`; joiner-side candidates forwarded to host as before; stale `joinerCand` cleared on join and on disconnect.
- Verified: new socket-level test (`candidate-buffer-test.js`) reproduces the race → passes; existing relay regression test passes.

**Hygiene claims (all TRUE).**
1. `server/node_modules/` was tracked in git (20 files) — untracked with `git rm -r --cached` (kept on disk; `server/node_modules/` added to `.gitignore`; `git check-ignore` confirms).
2. Missing `gradlew` — generated (`gradle wrapper --gradle-version 8.9`), `./gradlew --version` → Gradle 8.9.
3. Reviewer note "git diff + new files only looked at tracked changes" — acknowledged; untracked build outputs (`build/`, `.gradle/`) are ignored now, and `.gitignore` was reviewed.

**Not changed deliberately:** port XOR (`0x2112`, the cookie's upper 16 bits — was and is correct), the legacy-3489 path (obsolete RFC; no need to accept `0x0001` now that 442 is in place), relay/architecture (D3/D12 unchanged), signaling `ws://` cleartext. Verified nothing else depended on the wrong constant (`sawV4Only` path unaffected — family-gated, cookie-checked).

**Status after this pass:** direct IPv6 punching is now *capable* end-to-end on real devices (STUN works against live servers; candidate flush works; ADM adoption works in tests; failure paths land in `RELAYED`). On-device validation of the Vi/Airtel/Jio matrix (D6) remains the only unverified step — nothing here can substitute for it.
