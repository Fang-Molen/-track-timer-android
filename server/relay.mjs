// 跑道计时中转服务器（track-timer relay）。无第三方依赖，Node 18+。
// 只转发两台手机之间的短消息（对时探测、发令状态），不保存视频、不写消息内容日志。
// 默认只监听 127.0.0.1，由 nginx 提供 wss:// (TLS) 反向代理。
import http from 'node:http';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const DEFAULTS = {
  port: 8790, host: '127.0.0.1', path: '/tt-relay',
  codeTtlMs: 10 * 60_000,      // 配对码 10 分钟有效
  roomIdleMs: 30 * 60_000,     // 房间空闲 30 分钟删除
  maxFailures: 5,              // 同一 IP 10 分钟内输错 5 次锁定
  failureWindowMs: 10 * 60_000,
  maxRooms: 200, maxMessage: 1024,
};
const FORBIDDEN_PORTS = new Set([3210]);
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

export function createRelay(opts = {}) {
  const o = { ...DEFAULTS, ...opts };
  if (FORBIDDEN_PORTS.has(Number(o.port))) throw new Error(`port ${o.port} is reserved`);
  const now = o.now || (() => Date.now());
  const rooms = new Map();      // roomId -> {code, codeExpires, codeUsed, host, peer, touched}
  const codes = new Map();      // code -> roomId (only while unused & valid)
  const failures = new Map();   // ip -> [timestamps]
  const t0 = process.hrtime.bigint();

  const newCode = () => { for (let i = 0; i < 50; i++) { const c = String(crypto.randomInt(100000, 1000000)); if (!codes.has(c)) return c; } throw new Error('NO_CODE'); };
  const locked = ip => { const a = (failures.get(ip) || []).filter(t => now() - t < o.failureWindowMs); failures.set(ip, a); return a.length >= o.maxFailures; };
  const fail = ip => { const a = failures.get(ip) || []; a.push(now()); failures.set(ip, a); };

  function frame(sock, op, buf) {
    const n = buf.length; let h;
    if (n < 126) h = Buffer.from([0x80 | op, n]);
    else if (n < 65536) { h = Buffer.alloc(4); h[0] = 0x80 | op; h[1] = 126; h.writeUInt16BE(n, 2); }
    else return;
    if (!sock.destroyed) sock.write(Buffer.concat([h, buf]));
  }
  const send = (c, obj) => c && !c.sock.destroyed && frame(c.sock, 1, Buffer.from(JSON.stringify(obj)));
  const close = (c, code = 1000) => { const b = Buffer.alloc(2); b.writeUInt16BE(code); frame(c.sock, 8, b); c.sock.end(); };
  const other = (r, c) => (r.host === c ? r.peer : r.host);

  function drop(roomId) { const r = rooms.get(roomId); if (!r) return; codes.delete(r.code); rooms.delete(roomId);
    for (const c of [r.host, r.peer]) if (c) { send(c, { t: 'closed' }); close(c); } }

  function onMessage(c, j) {
    if (j.t === 'time') return send(c, { t: 'time', id: j.id, s: String(process.hrtime.bigint() - t0) });
    if (j.t === 'create') {
      if (c.room) return send(c, { t: 'error', code: 'ALREADY' });
      if (rooms.size >= o.maxRooms) return send(c, { t: 'error', code: 'FULL' });
      const roomId = crypto.randomUUID(), code = newCode();
      rooms.set(roomId, { code, codeExpires: now() + o.codeTtlMs, codeUsed: false, host: c, peer: null, touched: now() });
      codes.set(code, roomId); c.room = roomId; c.role = 'host';
      return send(c, { t: 'created', code, room: roomId, ttl: o.codeTtlMs });
    }
    if (j.t === 'join') {
      if (c.room) return send(c, { t: 'error', code: 'ALREADY' });
      if (locked(c.ip)) return send(c, { t: 'error', code: 'LOCKED' });
      const roomId = codes.get(String(j.code || '')); const r = roomId && rooms.get(roomId);
      if (!r || r.codeUsed || now() > r.codeExpires) { fail(c.ip); return send(c, { t: 'error', code: r && now() > r.codeExpires ? 'EXPIRED' : 'BAD_CODE' }); }
      r.codeUsed = true; codes.delete(r.code); r.peer = c; r.touched = now(); c.room = roomId; c.role = 'peer';
      r.resume = crypto.randomBytes(18).toString('base64url');
      send(c, { t: 'joined', room: roomId, resume: r.resume }); return send(r.host, { t: 'peer', state: 'joined' });
    }
    if (j.t === 'resume') {   // finish phone reconnecting after a network drop (code is already used)
      if (c.room) return send(c, { t: 'error', code: 'ALREADY' });
      if (locked(c.ip)) return send(c, { t: 'error', code: 'LOCKED' });
      const r = rooms.get(String(j.room || ''));
      const ok = r && r.resume && typeof j.resume === 'string' && j.resume.length === r.resume.length &&
        crypto.timingSafeEqual(Buffer.from(j.resume), Buffer.from(r.resume));
      if (!ok) { fail(c.ip); return send(c, { t: 'error', code: 'BAD_RESUME' }); }
      if (r.peer && r.peer !== c) { const old = r.peer; r.peer = null; old.room = null; close(old); }
      r.peer = c; r.touched = now(); c.room = j.room; c.role = 'peer';
      send(c, { t: 'joined', room: j.room, resume: r.resume }); return send(r.host, { t: 'peer', state: 'joined' });
    }
    if (j.t === 'fwd') {
      const r = c.room && rooms.get(c.room); if (!r) return send(c, { t: 'error', code: 'NO_ROOM' });
      const to = other(r, c); r.touched = now();
      if (!to) return send(c, { t: 'error', code: 'NO_PEER', id: j.id });
      if (typeof j.d !== 'string' || j.d.length > 600) return send(c, { t: 'error', code: 'BAD_MSG' });
      return send(to, { t: 'fwd', id: j.id, d: j.d });
    }
    send(c, { t: 'error', code: 'UNKNOWN' });
  }

  function onClose(c) {
    const r = c.room && rooms.get(c.room); if (!r) return;
    if (r.host === c) drop(c.room);            // 起点离开：本场作废
    else if (r.peer === c) { r.peer = null; send(r.host, { t: 'peer', state: 'left' }); }
  }

  function attach(sock, ip) {
    const c = { sock, ip, room: null, role: null, buf: Buffer.alloc(0), alive: true };
    sock.setNoDelay(true);
    sock.on('data', d => {
      c.buf = Buffer.concat([c.buf, d]);
      while (c.buf.length >= 2) {
        const op = c.buf[0] & 0x0f, masked = c.buf[1] & 0x80; let len = c.buf[1] & 0x7f, off = 2;
        if (len === 126) { if (c.buf.length < 4) return; len = c.buf.readUInt16BE(2); off = 4; }
        else if (len === 127) return sock.destroy();
        if (!masked || len > o.maxMessage) return sock.destroy();
        if (c.buf.length < off + 4 + len) return;
        const mask = c.buf.subarray(off, off + 4), p = Buffer.from(c.buf.subarray(off + 4, off + 4 + len));
        for (let i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
        c.buf = c.buf.subarray(off + 4 + len);
        if (op === 8) { close(c); return; }
        if (op === 9) { frame(sock, 10, p); continue; }
        if (op === 10) { c.alive = true; continue; }
        if (op !== 1) continue;
        let j; try { j = JSON.parse(p.toString('utf8')); } catch { return sock.destroy(); }
        if (j && typeof j === 'object') onMessage(c, j);
      }
    });
    sock.on('close', () => onClose(c)); sock.on('error', () => {});
    return c;
  }

  const server = http.createServer((req, res) => {
    if (req.url === '/healthz') { res.writeHead(200, { 'content-type': 'text/plain' }); return res.end('ok'); }
    res.writeHead(404); res.end();
  });
  const conns = new Set();
  server.on('upgrade', (req, sock) => {
    const key = req.headers['sec-websocket-key'];
    if (req.url !== o.path || !key || (req.headers.upgrade || '').toLowerCase() !== 'websocket') { sock.end('HTTP/1.1 400 Bad Request\r\n\r\n'); return; }
    const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
    sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    const ip = (o.trustProxy ? String(req.headers['x-real-ip'] || '') : '') || sock.remoteAddress || '?';
    const c = attach(sock, ip); conns.add(c); sock.on('close', () => conns.delete(c));
  });
  const sweep = setInterval(() => {
    for (const [id, r] of rooms) if (now() - r.touched > o.roomIdleMs || (!r.codeUsed && now() > r.codeExpires && !r.peer)) drop(id);
    for (const c of conns) { if (!c.alive) { c.sock.destroy(); continue; } c.alive = false; frame(c.sock, 9, Buffer.alloc(0)); }
  }, o.sweepMs || 20_000); sweep.unref();
  return {
    listen: () => new Promise(r => server.listen(o.port, o.host, () => r(server.address().port))),
    close: () => new Promise(r => { clearInterval(sweep); for (const c of conns) c.sock.destroy(); server.close(() => r()); }),
    stats: () => ({ rooms: rooms.size, codes: codes.size }),
  };
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  const port = Number(process.env.PORT || DEFAULTS.port);
  const relay = createRelay({ port, host: process.env.HOST || DEFAULTS.host, path: process.env.WS_PATH || DEFAULTS.path, trustProxy: process.env.TRUST_PROXY === '1' });
  relay.listen().then(p => console.log(`track-timer relay listening on ${process.env.HOST || DEFAULTS.host}:${p}${process.env.WS_PATH || DEFAULTS.path}`));
  for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => relay.close().then(() => process.exit(0)));
}
