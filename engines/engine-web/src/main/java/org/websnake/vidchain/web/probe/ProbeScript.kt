package org.websnake.vidchain.web.probe

/**
 * The media probe that runs inside every frame of a page (T8.11, owner checklist B "a killer grabber"). Own code; the
 * ideas come from studying how browser extensions and Web Video Caster find videos (research notes in the project
 * folder). It only REPORTS addresses; it never changes what the page loads or plays (except the optional autoplay of
 * the hidden catcher page).
 *
 * What it reports, each with a "how" tag:
 *  - net / ct / ct-hls / ct-dash: fetch and XHR traffic, classified by address and by the response Content-Type
 *  - body-hls / body-dash / body: a response that IS a playlist, or text and JSON responses (and JSON.parse input)
 *    that carry media addresses: players often get their stream address from an API, not from a request we can see
 *  - media.src / play / dom: what <video>/<audio>/<source> elements are given and what they actually play
 *  - player: the state of common web players (JW Player, Flowplayer, Clappr, Video.js, OK/VK, Vimeo config)
 *  - meta: og:video / twitter:player:stream tags
 *  - perf: the page's own Resource Timing list (anything loaded before the hooks were in place)
 *  - blob: the page plays from a MediaSource (reported once, without an address)
 */
object ProbeScript {
	/** name of the reporting object in the page (WebMessageListener object, or the JavaScript interface fallback) */
	const val NAME = "VidChainProbe"

	fun build(autoplay: Boolean): String = JS.replace("__AUTOPLAY__", if (autoplay) "true" else "false")

	private val JS = """
(function(){
  if (window.__vcProbe) return; window.__vcProbe = 1;
  var AUTOPLAY = __AUTOPLAY__, sent = {}, n = 0;
  var MEDIA = /\.(m3u8|mpd|mp4|m4v|webm|mov|mkv|flv|f4v|m4a|mp3|ogg|ogv|oga|aac|opus)(?:[?#&;\/]|$)|\.ism\/manifest|[?&]mime=(?:video|audio)|DASHPlaylist|HLSPlaylist/i;
  var TEXTURL = /https?:\\?\/\\?\/[^"'\s<>]{4,2000}?\.(?:m3u8|mpd|mp4|m4v|webm|mov|m4a|mp3)(?:\?[^"'\s<>]{0,1500})?(?=["'\s<>,\\)]|$)/gi;
  function post(m){ try { var B = window.$NAME; if (!B) return; if (typeof B.postMessage === 'function') B.postMessage(m); else if (typeof B.report === 'function') B.report(m); } catch(e){} }
  function rep(u, how){
    try {
      if (!u) return; u = String(u); if (u.length > 6000) return;
      if (u.indexOf('blob:') === 0) { if (!sent.blob) { sent.blob = 1; post(JSON.stringify({u:'blob:', h:'blob', p:location.href})); } return; }
      if (u.indexOf('data:') === 0) return;
      try { u = new URL(u, document.baseURI).href; } catch(e){ return; }
      if (!/^https?:/i.test(u)) return;
      var k = how + '|' + u; if (sent[k]) return; sent[k] = 1; if (++n > 600) return;
      post(JSON.stringify({u:u, h:how, p:location.href}));
    } catch(e){}
  }
  function unesc(s){ return s.replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/&amp;/g, '&'); }
  function scanText(t, base){
    try {
      if (typeof t !== 'string' || !t || t.length > 4000000) return;
      var head = t.slice(0, 600);
      if (/^\s*#EXTM3U/.test(head)) { rep(base, 'body-hls'); return; }
      if (/<MPD[\s>]/.test(head)) { rep(base, 'body-dash'); return; }
      if (!/m3u8|mpd|mp4|webm|mov|m4a|mp3/i.test(t)) return;
      var m, c = 0; TEXTURL.lastIndex = 0;
      while ((m = TEXTURL.exec(t)) && c++ < 60) rep(unesc(m[0]), 'body');
    } catch(e){}
  }
  function inspect(url, ct, len, body){
    try {
      ct = (ct || '').toLowerCase();
      if (/mpegurl/.test(ct)) { rep(url, 'ct-hls'); return; }
      if (/dash\+xml/.test(ct)) { rep(url, 'ct-dash'); return; }
      if (/^(video|audio)\//.test(ct)) { rep(url, 'ct'); return; }
      if (MEDIA.test(url || '')) rep(url, 'net');
      if (!body) return;
      if (/json|text|javascript|xml/.test(ct) && !(len > 4000000)) body();
      else if ((ct === '' || /octet-stream|binary/.test(ct)) && len > 0 && len < 300000) body();   // disguised playlists
    } catch(e){}
  }
  try {
    var of = window.fetch;
    if (of) window.fetch = function(i, o){
      var p = of.apply(this, arguments);
      try {
        var u = (i && i.url) || i;
        p.then(function(r){ try {
          var ct = r.headers.get('content-type'), len = +r.headers.get('content-length') || 0, ru = r.url || u;
          inspect(ru, ct, len, function(){ r.clone().text().then(function(t){ scanText(t, ru); }).catch(function(){}); });
        } catch(e){} }).catch(function(){});
      } catch(e){}
      return p;
    };
  } catch(e){}
  try {
    var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){ try { this.__vcU = u; } catch(e){} return oo.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      var x = this;
      try { x.addEventListener('load', function(){ try {
        var ru = x.responseURL || x.__vcU, ct = x.getResponseHeader('content-type'), len = +x.getResponseHeader('content-length') || 0;
        inspect(ru, ct, len, function(){
          if (x.responseType === '' || x.responseType === 'text') scanText(x.responseText, ru);
          else if (x.responseType === 'json' && x.response) { try { scanText(JSON.stringify(x.response), ru); } catch(e){} }
        });
      } catch(e){} }); } catch(e){}
      return os.apply(this, arguments);
    };
  } catch(e){}
  try { var jp = JSON.parse; JSON.parse = function(s){ var r = jp.apply(this, arguments); try { if (typeof s === 'string' && s.length > 40) scanText(s, location.href); } catch(e){} return r; }; } catch(e){}
  try { var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (d && d.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', { set: function(v){ rep(v, 'media.src'); return d.set.call(this, v); }, get: d.get, configurable: true }); } catch(e){}
  try { var oc = URL.createObjectURL; URL.createObjectURL = function(o){ var u = oc.apply(this, arguments); try { if (o && o.constructor && o.constructor.name === 'MediaSource') rep(u, 'blob'); } catch(e){} return u; }; } catch(e){}
  function onPlay(e){ try { var v = e.target; if (v && v.currentSrc) rep(v.currentSrc, 'play'); } catch(x){} }
  try { document.addEventListener('play', onPlay, true); document.addEventListener('loadedmetadata', onPlay, true); } catch(e){}
  function attr(sel, a, how){ try { document.querySelectorAll(sel).forEach(function(e){ rep(e.getAttribute(a), how); }); } catch(e){} }
  function players(){
    try { if (typeof jwplayer === 'function') { var pl = jwplayer().getPlaylist() || []; pl.forEach(function(it){ (it.sources || []).forEach(function(s){ rep(s.file, 'player'); }); if (it.file) rep(it.file, 'player'); }); } } catch(e){}
    try { if (typeof flowplayer === 'function') { var f = flowplayer(); if (f && f.video) rep(f.video.src, 'player'); } } catch(e){}
    try { if (window.Clappr && Clappr.PlayerInfo && Clappr.PlayerInfo._players) { var ps = Clappr.PlayerInfo._players; for (var k in ps) { try { rep(ps[k].options.source, 'player'); } catch(e){} } } } catch(e){}
    try { if (window.videojs && typeof videojs.getPlayers === 'function') { var vp = videojs.getPlayers(); for (var k2 in vp) { try { rep(vp[k2].currentSrc(), 'player'); } catch(e){} } } } catch(e){}
    try { if (typeof OneVideoPlayer === 'object') { var ov = OneVideoPlayer.getInstance(), md = ov && ov.options && ov.options.flashvars && ov.options.flashvars.metadata; if (md) { rep(md.hlsManifestUrl, 'player'); (md.videos || []).forEach(function(v){ rep(v.url, 'player'); }); } } } catch(e){}
    try { var pc = window.playerConfig, fl = pc && pc.request && pc.request.files; if (fl) { try { var h = fl.hls; if (h && h.cdns) for (var c in h.cdns) rep(h.cdns[c].url, 'player'); } catch(e){} try { (fl.progressive || []).forEach(function(p){ rep(p.url, 'player'); }); } catch(e){} } } catch(e){}
  }
  function scan(){
    try { document.querySelectorAll('video,audio').forEach(function(e){ rep(e.currentSrc, 'dom'); rep(e.getAttribute('src'), 'dom'); rep(e.getAttribute('data-src'), 'dom'); }); } catch(e){}
    attr('video source[src],audio source[src]', 'src', 'dom');
    attr('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]', 'content', 'meta');
    players();
    try { performance.getEntriesByType('resource').forEach(function(r){ if (r.initiatorType === 'video' || r.initiatorType === 'audio' || MEDIA.test(r.name)) rep(r.name, 'perf'); }); } catch(e){}
  }
  var pending = 0;
  function soon(){ if (pending) return; pending = 1; setTimeout(function(){ pending = 0; scan(); }, 400); }
  try { new MutationObserver(function(ms){ for (var i = 0; i < ms.length; i++) { var a = ms[i].addedNodes; for (var j = 0; j < a.length; j++) { var t = a[j].nodeName; if (t === 'VIDEO' || t === 'AUDIO' || t === 'SOURCE' || t === 'IFRAME' || (a[j].querySelector && a[j].querySelector('video,audio'))) { soon(); return; } } } }).observe(document, { childList: true, subtree: true }); } catch(e){}
  try { document.addEventListener('DOMContentLoaded', scan); window.addEventListener('load', scan); } catch(e){}
  setTimeout(scan, 2000); setTimeout(scan, 6000);
  function play(){ try { document.querySelectorAll('video').forEach(function(v){ v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function(){}); }); } catch(e){} }
  if (AUTOPLAY) { setTimeout(play, 1500); setTimeout(play, 5000); setInterval(scan, 1500); }
})();
""".trimIndent()
}
