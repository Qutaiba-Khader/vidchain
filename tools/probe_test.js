// Behaviour test of the VidChain media probe (engines/engine-web .../probe/ProbeScript.kt) in a mocked page; run by tools/probe_check.py (node 20+: fetch, Response, URL).
const out = [];
globalThis.window = globalThis;
globalThis.location = { href: 'https://site.example/watch/1' };
const listeners = {};
globalThis.document = { baseURI: 'https://site.example/watch/1', addEventListener: (t, f) => (listeners[t] = f), querySelectorAll: () => [] };
globalThis.MutationObserver = class { observe() {} };
globalThis.HTMLMediaElement = function () {}; HTMLMediaElement.prototype = {};
Object.defineProperty(HTMLMediaElement.prototype, 'src', { set(v) { this._s = v; }, get() { return this._s; }, configurable: true });
globalThis.XMLHttpRequest = function () { this.l = {}; }; XMLHttpRequest.prototype.open = function (m, u) { this.u = u; };
XMLHttpRequest.prototype.send = function () {}; XMLHttpRequest.prototype.addEventListener = function (t, f) { const prev = this.l[t]; this.l[t] = prev ? () => { prev(); f(); } : f; };
globalThis.VidChainProbe = { postMessage: m => out.push(JSON.parse(m)) };
const bodies = {
  'https://api.example/hls': ['#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n', 'text/plain'],
  'https://api.example/info': [JSON.stringify({ video: { url: 'https://cdn.example/v/9.mp4?sig=a&b=c' }, other: 'https://cdn.example/page' }), 'application/json'],
  'https://api.example/mpd': ['<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011">', 'application/octet-stream'],
  'https://cdn.example/seg': ['xx', 'video/mp4'],
  // review regressions (T8.11 test-skills run): stream pieces, JSON that mentions an MPD, escaped text
  'https://cdn.example/hls/seg1.ts': ['xx', 'video/mp2t'],
  'https://cdn.example/dash/chunk-1.m4s': ['xx', 'video/iso.segment'],
  'https://rr1.googlevideo.com/videoplayback?mime=video%2Fmp4&range=0-1000': ['xx', 'video/mp4'],
  'https://api.example/graphql': [JSON.stringify({ video_dash_manifest: '<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011"></MPD>',
    video_versions: [{ url: 'https://cdn.example/ig/v.mp4?efg=1' }] }), 'application/json'],
  'https://api.example/wp': ['{"content":"<video src=\\"https:\\/\\/blog.example\\/talk.mp4?_=1\\\"><\\/video>"}', 'application/json'],
};
globalThis.fetch = async (u) => { const [b, ct] = bodies[u]; return new Response(b, { headers: { 'content-type': ct, 'content-length': String(b.length) } }); };
require(process.argv[2]);
(async () => {
  for (const u of Object.keys(bodies)) await fetch(u);
  JSON.parse('{"a":"https:\\/\\/cdn.example\\/x\\/m.m3u8?t=1\\u0026k=2"}');
  const x = new XMLHttpRequest(); x.open('GET', 'https://api.example/xhr'); x.send();
  x.responseURL = 'https://api.example/xhr'; x.responseType = ''; x.responseText = 'var s="https://cdn.example/a/b.mp4";';
  x.getResponseHeader = h => (h === 'content-type' ? 'text/javascript' : null); x.l.load();
  const v = new HTMLMediaElement(); v.src = 'blob:https://site.example/abc'; v.src = 'https://cdn.example/direct.webm';
  listeners.play && listeners.play({ target: { currentSrc: 'https://cdn.example/played' } });
  await new Promise(r => setTimeout(r, 300));
  const got = out.map(o => o.h + ' ' + o.u).sort();
  console.log(got.join('\n'));
  // an XHR object reused 3 times gets one load listener, not three
  const r = new XMLHttpRequest(); let reads = 0;
  r.getResponseHeader = h => (h === 'content-type' ? 'text/plain' : null); r.responseType = '';
  Object.defineProperty(r, 'responseText', { get() { reads++; return 'x'; } }); r.responseURL = 'https://api.example/re';
  for (let i = 0; i < 3; i++) { r.open('GET', 'https://api.example/re'); r.send(); r.l.load(); }
  if (reads !== 3) { console.log('FAIL xhr reuse read the body', reads, 'times'); process.exit(1); }
  if (JSON.parse.length !== 2) { console.log('FAIL JSON.parse arity', JSON.parse.length); process.exit(1); }
  const want = ['body-hls https://api.example/hls', 'body https://cdn.example/v/9.mp4?sig=a&b=c', 'body-dash https://api.example/mpd', 'ct https://cdn.example/seg',
    'body https://cdn.example/x/m.m3u8?t=1&k=2', 'body https://cdn.example/a/b.mp4', 'blob blob:', 'media.src https://cdn.example/direct.webm', 'play https://cdn.example/played'];
  const miss = want.filter(w => !got.includes(w));
  want.push('body https://cdn.example/ig/v.mp4?efg=1', 'body https://blog.example/talk.mp4?_=1');
  const bad = got.filter(g => g.includes('cdn.example/page') || /seg1\.ts|chunk-1\.m4s|range=|body-dash https:\/\/api\.example\/graphql|\\$/.test(g));
  if (miss.length || bad.length) { console.log('FAIL missing:', miss, 'unexpected:', bad); process.exit(1); }
  console.log('PASS', got.length, 'finds');
  process.exit(0);
})();
