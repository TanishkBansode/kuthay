const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const WS_PORT = process.env.WS_PORT || 8080;

const rooms = new Map();

function genCode() {
    const alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    let c;
    do {
        c = '';
        for (let i = 0; i < 4; i++) c += alphabet[crypto.randomInt(alphabet.length)];
    } while (rooms.has(c));
    return c;
}

function send(ws, obj) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}

function cleanV6(addr) {
    return String(addr || '').split('%')[0];
}

const server = http.createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('kuthay signaling up\n');
});

const wss = new WebSocketServer({ server, maxPayload: 64 * 1024 });

wss.on('connection', (ws, req) => {
    ws.seenAddr = cleanV6(req.socket.remoteAddress);
    console.log(`[ws] connect from ${ws.seenAddr} (v4-mapped=${ws.seenAddr.includes(':ffff:')})`);

    ws.on('message', (data) => {
        let m;
        try { m = JSON.parse(data.toString()); } catch { return; }

        switch (m.type) {
            case 'register': {
                const code = genCode();
                rooms.set(code, { host: ws, created: Date.now() });
                ws.room = code;
                ws.side = 'host';
                ws.label = m.label || '';
                ws.sid = m.sid || '';
                console.log(`[room] ${code} created host=${ws.seenAddr} label="${ws.label}" device="${m.device || ''}"`);
                send(ws, { type: 'registered', code, yourAddr: ws.seenAddr });
                break;
            }
            case 'join': {
                const room = rooms.get(String(m.code || '').toUpperCase());
                if (!room) return send(ws, { type: 'error', msg: 'no such room' });
                if (room.joiner && room.joiner.readyState === 1) return send(ws, { type: 'error', msg: 'room full' });
                room.joinerCand = null;   // drop any stale buffered candidate from a previous joiner
                room.joiner = ws;
                ws.room = room.host.room;
                ws.side = 'joiner';
                ws.label = m.label || '';
                ws.sid = m.sid || '';
                console.log(`[room] ${ws.room} joined joiner=${ws.seenAddr} label="${ws.label}" device="${m.device || ''}"`);
                send(ws, { type: 'joined', code: ws.room, yourAddr: ws.seenAddr });
                send(room.host, { type: 'ready', peerInfo: `${ws.seenAddr} "${ws.label}"` });
                send(ws, { type: 'ready', peerInfo: `${room.host.seenAddr} "${room.host.label}"` });
                // The host probes STUN and sends its candidate long before the joiner
                // connects — buffer it so the joiner doesn't vanish into a 30 s timeout.
                if (room.hostCand) {
                    send(ws, {
                        type: 'candidate',
                        ip: room.hostCand.ip,
                        port: room.hostCand.port,
                        sid: room.hostCand.sid || '',
                    });
                }
                break;
            }
            case 'candidate': {
                const room = rooms.get(ws.room);
                if (!room) return;
                console.log(`[cand] ${ws.room}/${ws.side} srflx=[${m.ip}]:${m.port}`);
                // Buffer the latest candidate per side; it may predate the peer's join.
                const cand = { ip: m.ip, port: m.port, sid: m.sid || '' };
                if (ws.side === 'host') room.hostCand = cand;
                else room.joinerCand = cand;
                const other = ws.side === 'host' ? room.joiner : room.host;
                if (other) send(other, { type: 'candidate', ip: cand.ip, port: cand.port, sid: cand.sid });
                break;
            }
            // Relay fallback (P2a): when direct P2P is impossible (symmetric CGNAT,
            // IPv4-only, NAT64), chat frames ride the signaling WebSocket.
            case 'relay-request': {
                const room = rooms.get(ws.room);
                if (!room) return;
                console.log(`[relay-request] ${ws.room}/${ws.side} ${ws.seenAddr}`);
                const other = ws.side === 'host' ? room.joiner : room.host;
                send(other, { type: 'relay-request' });
                break;
            }
            case 'relay-msg': {
                const room = rooms.get(ws.room);
                if (!room) return;
                const other = ws.side === 'host' ? room.joiner : room.host;
                send(other, { type: 'relay-msg', text: String(m.text || '').slice(0, 4096) });
                break;
            }
            case 'result': {
                console.log(`[RESULT] room=${ws.room} side=${ws.side} label="${ws.label}" ok=${m.ok} note="${m.note || ''}"`);
                const room = rooms.get(ws.room);
                const other = room ? (ws.side === 'host' ? room.joiner : room.host) : null;
                send(other, { type: 'result', ok: m.ok, note: m.note });
                break;
            }
        }
    });

    ws.on('close', () => {
        console.log(`[ws] close from ${ws.seenAddr} room=${ws.room}`);
        if (!ws.room) return;
        const room = rooms.get(ws.room);
        if (room) {
            const other = ws.side === 'host' ? room.joiner : room.host;
            send(other, { type: 'error', msg: 'peer disconnected' });
            if (ws.side === 'host') {
                // 5G/4G handover blips drop the WebSocket for a second — a joiner
                // knocking after that blip must still be able to use the room code.
                const code = ws.room;
                setTimeout(() => {
                    const r = rooms.get(code);
                    if (r && r.host === ws && (!r.joiner || r.joiner.readyState !== 1)) {
                        rooms.delete(code);
                    }
                }, 30_000);
            } else {
                room.joiner = null;
                room.joinerCand = null;
            }
        }
    });
});

setInterval(() => {
    const now = Date.now();
    for (const [code, room] of rooms) {
        if ((!room.joiner || !room.joiner.readyState) && now - room.created > 3600_000) rooms.delete(code);
    }
}, 60_000);

server.listen(WS_PORT, () => console.log(`[ws] listening tcp :${WS_PORT}`));
