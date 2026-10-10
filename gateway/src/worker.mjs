// One invocation performs at most ONE read operation, including errors/Range/HEAD.
// Keep the account on Workers Free (100,000 HTTP requests/day, hard fail closed).
// Under these conditions this gateway contributes at most ~3.2 million reads per
// 31-day billing period, including a partial UTC day at either boundary.
export function parseRange(value) {
  if (!value) return undefined;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2])) throw new Error('invalid range');
  if (!match[1]) {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) throw new Error('invalid suffix');
    return { suffix };
  }
  const offset = Number(match[1]);
  const end = match[2] ? Number(match[2]) : undefined;
  if (!Number.isSafeInteger(offset) || offset < 0 || (end !== undefined && (!Number.isSafeInteger(end) || end < offset))) throw new Error('invalid offset');
  if (end !== undefined && !Number.isSafeInteger(end - offset + 1)) throw new Error('invalid length');
  return end === undefined ? { offset } : { offset, length: end - offset + 1 };
}
function reply(status, text, extra = {}) {
  return new Response(text, { status, headers: { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', ...extra } });
}
export default {
  async fetch(request, env) {
    if (env.ENABLED !== 'true') return reply(503, 'Media gateway disabled');
    if (request.method !== 'GET' && request.method !== 'HEAD') return reply(405, 'Read only', { Allow: 'GET, HEAD' });
    const url = new URL(request.url);
    if (!env.MEDIA_TOKEN || env.MEDIA_TOKEN.length < 32) return reply(503, 'Media gateway not configured');
    if (url.searchParams.get('token') !== env.MEDIA_TOKEN) return reply(403, 'Forbidden');
    if (!/^\/media\/[A-Za-z0-9_-]+\.mp4$/.test(url.pathname)) return reply(404, 'Not found');
    const key = url.pathname.substring(1);
    let range;
    // Ignoring Range is legal; without a second read we cannot validate If-Range.
    try { range = request.method === 'HEAD' || request.headers.has('If-Range') ? undefined : parseRange(request.headers.get('Range')); }
    catch { return reply(400, 'Use one valid byte range'); }
    let object;
    try {
      object = request.method === 'HEAD' ? await env.MEDIA.head(key)
        : await env.MEDIA.get(key, range ? { range } : undefined);
    } catch {
      // No retry or second HEAD: an R2 error must not multiply billed operations.
      return reply(502, 'R2 read failed');
    }
    if (!object) return reply(404, 'Not found');
    const headers = new Headers({
      'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes',
      'Cache-Control': 'private, no-store', 'Referrer-Policy': 'no-referrer',
      'X-Content-Type-Options': 'nosniff', 'ETag': object.httpEtag,
    });
    let status = 200, length = object.size;
    if (range && request.method !== 'HEAD') {
      const offset = object.range?.offset ?? (range.suffix ? Math.max(0, object.size - range.suffix) : range.offset);
      length = object.range?.length ?? Math.min(range.length ?? range.suffix ?? object.size, object.size - offset);
      if (offset >= object.size || length <= 0) {
        if (object.body) await object.body.cancel();
        return reply(416, 'Range not satisfiable', { 'Content-Range': `bytes */${object.size}` });
      }
      headers.set('Content-Range', `bytes ${offset}-${offset + length - 1}/${object.size}`);
      status = 206;
    }
    headers.set('Content-Length', String(length));
    return new Response(request.method === 'HEAD' ? null : object.body, { status, headers });
  },
};
