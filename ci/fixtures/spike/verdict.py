#!/usr/bin/env python3
"""Spike verdict (T1.1): the fixture world decrypts traffic from all three HTTP stacks of the
unmodified release APK. Classification by request fingerprints (mitmproxy sees no process names), measured in the first run:
  WebView : X-Requested-With: org.websnake.vidchain (the in-app browser uses the app's own User-Agent)
  OkHttp  : User-Agent okhttp/<version>
  yt-dlp  : Title-Case "Sec-Fetch-Mode" header without X-Requested-With (Python urllib keeps yt-dlp's header case)
usage: verdict.py flows.jsonl"""
import collections
import json
import sys

recs = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
reqs = [r for r in recs if r["kind"] == "request"]
fails = [r for r in recs if r["kind"] == "tls_failed_client"]
stacks = collections.defaultdict(list)
PKG = "org.websnake.vidchain"
for r in reqs:
    ua, order = r["ua"], r["header_order"]
    if r["x_requested_with"] == PKG:
        stacks["webview"].append(r)              # Android WebView names the embedding app
    elif ua.startswith("okhttp/"):
        stacks["okhttp"].append(r)               # the app's OkHttp client
    elif "Sec-Fetch-Mode" in order and not r["x_requested_with"]:
        stacks["yt-dlp"].append(r)               # Python urllib keeps yt-dlp's Title-Case std headers
    else:
        stacks["other"].append(r)                # Android system / Google apps on the emulator image
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
