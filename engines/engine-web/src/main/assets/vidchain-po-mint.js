/* vidchain-po-mint.js - VidChain (T8.12): mints YouTube PO tokens with BgUtils (vidchain-bgutils.js, loaded first).
 * Runs only in VidChain's own hidden page (https://www.youtube.com/vidchain-po?v=<video id>), served by the app, never
 * inside a real site. Every request leaves without cookies, so the tokens belong to a fresh logged-out visitor and the
 * user's own YouTube session is neither used nor changed. The result goes to the app once, through VidChainPo.done.
 */
(function () {
  'use strict';
  var REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
  var sent = false;
  function send(o) {
    if (sent) return;
    sent = true;
    try { window.VidChainPo.done(JSON.stringify(o)); } catch (e) { /* the app is gone */ }
  }
  var nativeFetch = window.fetch.bind(window);
  /** every request: no cookies sent, none stored */
  function get(url, init) {
    var o = {};
    if (init) for (var k in init) o[k] = init[k];
    o.credentials = 'omit';
    o.cache = 'no-store';
    return nativeFetch(url, o);
  }

  async function visitorData() {
    var r = await get('/sw.js_data');
    if (!r.ok) throw new Error('sw.js_data ' + r.status);
    var text = await r.text();
    if (text.indexOf(")]}'") === 0) text = text.slice(4);
    var d = JSON.parse(text);
    var v = d && d[0] && d[0][2] && d[0][2][0] && d[0][2][0][0] && d[0][2][0][0][13];
    if (typeof v !== 'string' || !v) throw new Error('no visitor data');
    return v;
  }

  async function run() {
    var bg = window.VidChainBg;
    if (!bg) throw new Error('BgUtils missing');
    var videoId = new URLSearchParams(location.search).get('v') || '';
    if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) throw new Error('bad video id');
    var visitor = await visitorData();
    var challenge = await bg.getChallenge({ requestKey: REQUEST_KEY, fetchFunction: get, useYouTubeAPI: true });
    var js = challenge.interpreterJavascript && challenge.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (!js) throw new Error('no interpreter');
    new Function(js)();
    var client = await bg.BotGuardClient.create({ program: challenge.program, globalName: challenge.globalName, globalObject: window });
    var signals = [];
    var snapshot = await client.snapshot({ webPoSignalOutput: signals }, 10000);
    var it = await get(bg.buildURL('GenerateIT', true), { method: 'POST', headers: bg.getHeaders(), body: JSON.stringify([REQUEST_KEY, snapshot]) });
    if (!it.ok) throw new Error('GenerateIT ' + it.status);
    var integrity = await it.json();
    if (!integrity || typeof integrity[0] !== 'string') throw new Error('no integrity token');
    var minter = await bg.WebPoMinter.create({ integrityToken: integrity[0] }, signals);
    var gvs = await minter.mintAsWebsafeString(visitor);
    var player = await minter.mintAsWebsafeString(videoId);
    send({ ok: true, visitorData: visitor, gvs: gvs, player: player, ttl: typeof integrity[1] === 'number' ? integrity[1] : 0 });
  }

  run().catch(function (e) { send({ ok: false, error: String((e && e.message) || e).slice(0, 160) }); });
})();
