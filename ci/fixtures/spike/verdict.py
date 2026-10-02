#!/usr/bin/env python3
"""Spike verdict (T1.1): the fixture world decrypts traffic from all three HTTP stacks of the
unmodified release APK. Classification by request fingerprints (mitmproxy sees no process names):
  WebView : User-Agent contains "; wv)" (Android WebView marker)
  OkHttp  : User-Agent starts with "okhttp/" OR header order typical of OkHttp (Host, Connection: Keep-Alive, Accept-Encoding: gzip)
  yt-dlp  : Sec-Fetch-Mode: navigate without the WebView marker, or the yt-dlp default headers
usage: verdict.py flows.jsonl"""
import collections
import json
import sys

recs = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
reqs = [r for r in recs if r["kind"] == "request"]
fails = [r for r in recs if r["kind"] == "tls_failed_client"]
stacks = collections.defaultdict(list)
for r in reqs:
    ua, order = r["ua"], [h.lower() for h in r["header_order"]]
    if "; wv)" in ua:
        stacks["webview"].append(r)
    elif ua.lower().startswith("okhttp/") or (r["connection"].lower() == "keep-alive" and r["accept_encoding"] == "gzip"):
        stacks["okhttp"].append(r)
    elif r["sec_fetch_mode"] == "navigate" or "youtubei" in r["path"] or r["path"].startswith("/watch"):
        stacks["yt-dlp"].append(r)
    else:
        stacks["other"].append(r)
print(f"{len(reqs)} decrypted requests, {len(fails)} refused TLS handshakes")
for k in ("webview", "okhttp", "yt-dlp", "other"):
    v = stacks.get(k, [])
    print(f"  {k:8s} {len(v):4d}  " + ", ".join(sorted({f'{r['host']}{r['path'][:40]}' for r in v})[:6]))
for f in fails[:10]:
    print(f"  refused TLS: sni={f['sni']} {f['error']}")
missing = [k for k in ("webview", "okhttp", "yt-dlp") if not stacks.get(k)]
if missing:
    print("FAIL: no decrypted traffic from: " + ", ".join(missing))
    sys.exit(1)
print("PASS: decrypted traffic from WebView, OkHttp and yt-dlp - the fixture world works without touching the APK")
