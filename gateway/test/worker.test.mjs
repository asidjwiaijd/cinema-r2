import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.mjs';
const token = 'a'.repeat(64);
function fixture(overrides = {}) {
  const calls = [];
  const data = new Uint8Array(100).map((_, i) => i);
  const env = { ENABLED: 'true', MEDIA_TOKEN: token, MEDIA: {
    async head(key) { calls.push(['head', key]); return { size: data.length, httpEtag: '"test"' }; },
    async get(key, opts) {
      calls.push(['get', key, opts]);
      let offset = opts?.range?.offset ?? Math.max(0, data.length - (opts?.range?.suffix ?? data.length));
      let length = Math.min(opts?.range?.length ?? data.length, data.length - offset);
      if (offset >= data.length) return { size: data.length, httpEtag: '"test"', range: { offset, length: 0 }, body: new Blob([]).stream() };
      return { size: data.length, httpEtag: '"test"', range: opts ? { offset, length } : undefined, body: new Blob([data.slice(offset, offset + length)]).stream() };
    },
  }, ...overrides };
  return { env, calls };
}
function request(method = 'GET', headers = {}, path = `/media/film.mp4?token=${token}`) {
  return new Request(`https://cinema.example${path}`, { method, headers });
}
test('GET streams entire object using exactly one R2 read', async () => {
  const { env, calls } = fixture(); const r = await worker.fetch(request(), env);
  assert.equal(r.status, 200); assert.equal((await r.arrayBuffer()).byteLength, 100); assert.equal(calls.length, 1);
});
test('HEAD has no body and exactly one head operation', async () => {
  const { env, calls } = fixture(); const r = await worker.fetch(request('HEAD'), env);
  assert.equal(r.headers.get('Content-Length'), '100'); assert.equal(await r.text(), ''); assert.equal(calls[0][0], 'head'); assert.equal(calls.length, 1);
});
for (const [value, expected, length] of [['bytes=10-19', 'bytes 10-19/100', 10], ['bytes=90-', 'bytes 90-99/100', 10], ['bytes=-10', 'bytes 90-99/100', 10], ['bytes=90-999', 'bytes 90-99/100', 10], ['bytes=-999', 'bytes 0-99/100', 100]]) {
  test(`Range ${value}`, async () => { const { env, calls } = fixture(); const r = await worker.fetch(request('GET', { Range: value }), env); assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), expected); assert.equal((await r.arrayBuffer()).byteLength, length); assert.equal(calls.length, 1); });
}
for (const value of ['bytes=1-2,5-6', 'bytes=20-10', 'bytes=-0', 'bytes=-', 'bytes=9007199254740992-', 'bytes=0-9007199254740991']) {
  test(`invalid Range rejects without R2: ${value}`, async () => { const { env, calls } = fixture(); assert.equal((await worker.fetch(request('GET', { Range: value }), env)).status, 400); assert.equal(calls.length, 0); });
}
test('out of bounds range performs no fallback read', async () => { const { env, calls } = fixture(); const r = await worker.fetch(request('GET', { Range: 'bytes=100-' }), env); assert.equal(r.status, 416); assert.equal(calls.length, 1); });
test('If-Range conservatively returns full representation with one read', async () => { const { env, calls } = fixture(); const r = await worker.fetch(request('GET', { Range: 'bytes=10-19', 'If-Range': '"old"' }), env); assert.equal(r.status, 200); assert.equal((await r.arrayBuffer()).byteLength, 100); assert.equal(calls.length, 1); });
test('disabled/auth/method/path errors make zero R2 calls', async () => {
  for (const [req, overrides, code] of [[request(), { ENABLED: 'false' }, 503], [request('GET', {}, '/media/film.mp4?token=bad'), {}, 403], [request('POST'), {}, 405], [request('GET', {}, `/media/no/film.mp4?token=${token}`), {}, 404], [request(), { MEDIA_TOKEN: '' }, 503]]) {
    const { env, calls } = fixture(overrides); assert.equal((await worker.fetch(req, env)).status, code); assert.equal(calls.length, 0);
  }
});
test('upstream failure is fail closed with one call and no retry', async () => {
  let calls = 0; const { env } = fixture({ MEDIA: { async get() { calls++; throw new Error('unavailable'); } } });
  assert.equal((await worker.fetch(request(), env)).status, 502); assert.equal(calls, 1);
});
