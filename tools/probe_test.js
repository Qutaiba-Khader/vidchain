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
XMLHttpRequest.prototype.send = function () {}; XMLHttpRequest.prototype.addEventListener = function (t, f) { this.l[t] = f; };
globalThis.VidChainProbe = { postMessage: m => out.push(JSON.parse(m)) };
const bodies = {
  'https://api.example/hls': ['#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n', 'text/plain'],
  'https://api.example/info': [JSON.stringify({ video: { url: 'https://cdn.example/v/9.mp4?sig=a&b=c' }, other: 'https://cdn.example/page' }), 'application/json'],
  'https://api.example/mpd': ['<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011">', 'application/octet-stream'],
  'https://cdn.example/seg': ['xx', 'video/mp4'],
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
  const want = ['body-hls https://api.example/hls', 'body https://cdn.example/v/9.mp4?sig=a&b=c', 'body-dash https://api.example/mpd', 'ct https://cdn.example/seg',
    'body https://cdn.example/x/m.m3u8?t=1&k=2', 'body https://cdn.example/a/b.mp4', 'blob blob:', 'media.src https://cdn.example/direct.webm', 'play https://cdn.example/played'];
  const miss = want.filter(w => !got.includes(w));
  const bad = got.filter(g => g.includes('cdn.example/page'));
  if (miss.length || bad.length) { console.log('FAIL missing:', miss, 'unexpected:', bad); process.exit(1); }
  console.log('PASS', got.length, 'finds');
  process.exit(0);
})();
