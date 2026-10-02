"""mitmproxy addon for the fixture-world spike (T1.1).

Hermetic: nothing is forwarded upstream. Every decrypted request is logged as one JSON line
(host, path, the headers that tell the HTTP stacks apart) and answered locally:
  fixture.vidchain.test  -> a tiny page with a <video> tag, and /video.mp4 -> a few bytes
  anything else          -> 404 "fixture world: no route"
TLS handshakes the client refused (CA not trusted) are logged too - they name the stack that
does not trust the fixture CA."""
import json
import os
import time

from mitmproxy import http, tls

LOG = os.environ.get("FIXTURE_FLOW_LOG", "flows.jsonl")
PAGE = b"""<!doctype html><html><head><title>VidChain fixture</title></head>
<body><video src="https://fixture.vidchain.test/video.mp4"></video></body></html>"""


def _write(rec):
    rec["t"] = round(time.time(), 3)
    with open(LOG, "a") as f:
        f.write(json.dumps(rec) + "\n")


def request(flow: http.HTTPFlow) -> None:
    h = flow.request.headers
    _write({"kind": "request", "scheme": flow.request.scheme, "host": flow.request.pretty_host,
            "method": flow.request.method, "path": flow.request.path[:200],
            "ua": h.get("user-agent", ""), "x_requested_with": h.get("x-requested-with", ""),
            "sec_fetch_mode": h.get("sec-fetch-mode", ""), "accept_encoding": h.get("accept-encoding", ""),
            "connection": h.get("connection", ""), "header_order": list(h.keys())[:12]})
    if flow.request.pretty_host == "fixture.vidchain.test":
        if flow.request.path.startswith("/video.mp4"):
            flow.response = http.Response.make(200, b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 64, {"Content-Type": "video/mp4"})
        else:
            flow.response = http.Response.make(200, PAGE, {"Content-Type": "text/html"})
    else:
        flow.response = http.Response.make(404, b"fixture world: no route", {"Content-Type": "text/plain"})


def tls_failed_client(data: tls.TlsData) -> None:
    _write({"kind": "tls_failed_client", "sni": data.context.client.sni or "",
            "error": str(getattr(data.conn, "error", "") or "")[:200]})
