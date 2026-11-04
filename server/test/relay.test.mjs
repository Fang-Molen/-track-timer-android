import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../relay.mjs';

async function client(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/tt-relay`);
  const q = []; const waiters = [];
  ws.onmessage = e => { const j = JSON.parse(e.data); const w = waiters.shift(); w ? w(j) : q.push(j); };
  await new Promise((r, x) => { ws.onopen = r; ws.onerror = x; });
  return { send: o => ws.send(JSON.stringify(o)), next: () => q.length ? Promise.resolve(q.shift()) : new Promise(r => waiters.push(r)), close: () => ws.close() };
}
async function setup(opts = {}) { let t = 0; const relay = createRelay({ port: 0, now: () => t, ...opts }); const port = await relay.listen(); return { relay, port, advance: ms => { t += ms; } }; }

test('rejects reserved port 3210', () => assert.throws(() => createRelay({ port: 3210 })));

test('pairing, forwarding both ways, single-use code', async () => {
  const { relay, port } = await setup();
  const h = await client(port); h.send({ t: 'create' });
  const created = await h.next(); assert.equal(created.t, 'created'); assert.match(created.code, /^[0-9]{6}$/);
  const p = await client(port); p.send({ t: 'join', code: created.code });
  assert.equal((await p.next()).t, 'joined'); assert.deepEqual(await h.next(), { t: 'peer', state: 'joined' });
  p.send({ t: 'fwd', id: 7, d: 'PROBE|123456|0' }); assert.deepEqual(await h.next(), { t: 'fwd', id: 7, d: 'PROBE|123456|0' });
  h.send({ t: 'fwd', id: 7, d: 'STAMP|0|s|1|2' }); assert.deepEqual(await p.next(), { t: 'fwd', id: 7, d: 'STAMP|0|s|1|2' });
  const third = await client(port); third.send({ t: 'join', code: created.code });
  assert.equal((await third.next()).code, 'BAD_CODE');
  [h, p, third].forEach(c => c.close()); await relay.close();
});

test('code expires after 10 minutes', async () => {
  const { relay, port, advance } = await setup();
  const h = await client(port); h.send({ t: 'create' }); const { code } = await h.next();
  advance(10 * 60_000 + 1);
  const p = await client(port); p.send({ t: 'join', code }); assert.equal((await p.next()).code, 'EXPIRED');
  h.close(); p.close(); await relay.close();
});

test('locks an address after 5 wrong codes', async () => {
  const { relay, port } = await setup();
  const h = await client(port); h.send({ t: 'create' }); const { code } = await h.next();
  const p = await client(port);
  for (let i = 0; i < 5; i++) { p.send({ t: 'join', code: code === '000000' ? '111111' : '000000' }); assert.equal((await p.next()).code, 'BAD_CODE'); }
  p.send({ t: 'join', code }); assert.equal((await p.next()).code, 'LOCKED');
  h.close(); p.close(); await relay.close();
});

test('host leaving closes the room for the peer', async () => {
  const { relay, port } = await setup();
  const h = await client(port); h.send({ t: 'create' }); const { code } = await h.next();
  const p = await client(port); p.send({ t: 'join', code }); await p.next(); await h.next();
  h.close(); assert.equal((await p.next()).t, 'closed');
  assert.equal(relay.stats().rooms, 0); await relay.close();
});

test('finish phone can resume after a drop with its resume token only', async () => {
  const { relay, port } = await setup();
  const h = await client(port); h.send({ t: 'create' }); const { code } = await h.next();
  const p = await client(port); p.send({ t: 'join', code }); const joined = await p.next(); await h.next();
  p.close(); assert.equal((await h.next()).state, 'left');
  const bad = await client(port); bad.send({ t: 'resume', room: joined.room, resume: 'x'.repeat(joined.resume.length) });
  assert.equal((await bad.next()).code, 'BAD_RESUME');
  const p2 = await client(port); p2.send({ t: 'resume', room: joined.room, resume: joined.resume });
  assert.equal((await p2.next()).t, 'joined'); assert.equal((await h.next()).state, 'joined');
  p2.send({ t: 'fwd', id: 1, d: 'x' }); assert.equal((await h.next()).d, 'x');
  [h, p2, bad].forEach(c => c.close()); await relay.close();
});
