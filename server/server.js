const http = require('http');
const crypto = require('crypto');
const dgram = require('dgram');
const { WebSocketServer } = require('ws');

const WS_PORT = process.env.WS_PORT || 8080;
const PROBE_PORT = process.env.PROBE_PORT || 5688;

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

const wss = new WebSocketServer({ server });

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
                console.log(`[room] ${code} created host=${ws.seenAddr} label="${ws.label}" device="${m.device || ''}"`);
                send(ws, { type: 'registered', code, yourAddr: ws.seenAddr });
                break;
            }
            case 'join': {
                const room = rooms.get(String(m.code || '').toUpperCase());
                if (!room) return send(ws, { type: 'error', msg: 'no such room' });
                if (room.joiner && room.joiner.readyState === 1) return send(ws, { type: 'error', msg: 'room full' });
                room.joiner = ws;
                ws.room = room.host.room;
                ws.side = 'joiner';
                ws.label = m.label || '';
                console.log(`[room] ${ws.room} joined joiner=${ws.seenAddr} label="${ws.label}" device="${m.device || ''}"`);
                send(ws, { type: 'joined', code: ws.room, yourAddr: ws.seenAddr });
                send(room.host, { type: 'ready', peerInfo: `${ws.seenAddr} "${ws.label}"` });
                send(ws, { type: 'ready', peerInfo: `${room.host.seenAddr} "${room.host.label}"` });
                break;
            }
            case 'candidate': {
                const room = rooms.get(ws.room);
                if (!room) return;
                console.log(`[cand] ${ws.room}/${ws.side} srflx=[${m.ip}]:${m.port}`);
                const other = ws.side === 'host' ? room.joiner : room.host;
                send(other, { type: 'candidate', ip: m.ip, port: m.port });
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
            if (ws.side === 'host') rooms.delete(ws.room);
            else room.joiner = null;
        }
    });
});

const udp = dgram.createSocket('udp6');

udp.on('message', (buf, rinfo) => {
    let m;
    try { m = JSON.parse(buf.toString()); } catch { return; }
    if (!m.kuthay || !m.sid) return;
    const ip = cleanV6(rinfo.address);
    const reply = JSON.stringify({ kuthay: true, sid: m.sid, ip, port: rinfo.port });
    udp.send(reply, rinfo.port, rinfo.address, () => {
        console.log(`[probe] reply to [${ip}]:${rinfo.port}`);
    });
});

udp.on('error', (e) => console.error(`[probe] udp error: ${e.message}`));

udp.bind(PROBE_PORT, () => {
    const a = udp.address();
    console.log(`[probe] listening on [${a.address}]:${a.port}`);
});

setInterval(() => {
    const now = Date.now();
    for (const [code, room] of rooms) {
        if ((!room.joiner || !room.joiner.readyState) && now - room.created > 3600_000) rooms.delete(code);
    }
}, 60_000);

server.listen(WS_PORT, () => console.log(`[ws] listening tcp :${WS_PORT}`));
