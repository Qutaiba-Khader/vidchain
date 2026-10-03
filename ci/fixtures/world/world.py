"""mitmproxy addon: the hermetic fixture world (T1.2).

Serves every route of fixtures.json from generated media (FIXTURE_MEDIA dir) and recorded cassettes
(FIXTURE_CASSETTES dir); anything else gets 404 "fixture world: no route" - nothing is forwarded upstream.
Faults: append ?fault=<name> to a URL, or set FIXTURE_FAULTS="<fixture-id>:<fault>,..." for a whole run.
Every request is logged as one JSON line (FIXTURE_FLOW_LOG) with the headers that tell the HTTP stacks apart.
"""
import asyncio
import json
import mimetypes
import os
import pathlib
import re
import time
import urllib.parse

from mitmproxy import http, tls

HERE = pathlib.Path(__file__).resolve().parent
SPEC = json.loads((HERE / "fixtures.json").read_text())
MEDIA = pathlib.Path(os.environ.get("FIXTURE_MEDIA", HERE / "media"))
CASSETTES = pathlib.Path(os.environ.get("FIXTURE_CASSETTES", HERE.parent / "cassettes"))
LOG = os.environ.get("FIXTURE_FLOW_LOG", "flows.jsonl")
RUN_FAULTS = dict(x.split(":", 1) for x in os.environ.get("FIXTURE_FAULTS", "").split(",") if ":" in x)
CONTROL = os.environ.get("FIXTURE_FAULTS_FILE")          # JSON {fixture-id: "fault" or "fault@N"}; re-read on change
_control = {"mtime": None, "faults": {}, "count": {}}


def _faults_now():
    """Run-wide faults merged with the control file. "fault@N" fails only from the N-th request of that
    fixture on (e.g. 500@2: the browser sniff works, the download then fails)."""
    if CONTROL and os.path.exists(CONTROL):
        m = os.path.getmtime(CONTROL)
        if m != _control["mtime"]:
            try:
                _control.update(mtime=m, faults=json.loads(open(CONTROL).read() or "{}"), count={})
            except ValueError:
                pass
    return {**RUN_FAULTS, **_control["faults"]}
mimetypes.add_type("application/vnd.apple.mpegurl", ".m3u8")
mimetypes.add_type("application/dash+xml", ".mpd")
mimetypes.add_type("video/mp2t", ".ts")
mimetypes.add_type("audio/mp4", ".m4a")
mimetypes.add_type("video/iso.segment", ".m4s")


def _split(url):
    u = urllib.parse.urlsplit(url)
    return u.hostname, u.path, u.query


ROUTES = []   # (host, path, query-or-None, fixture)
for fx in SPEC["fixtures"]:
    host, path, query = _split(fx["url"])
    ROUTES.append((host, path, query or None, fx))


def _cassettes():
    out = {}
    manifest = CASSETTES / "manifest.json"
    if manifest.exists():
        for c in json.loads(manifest.read_text())["cassettes"]:
            h, p, q = _split(c["url"])
            out[(h, p)] = c
    return out


CASSETTE_INDEX = _cassettes()


def _log(rec):
    rec["t"] = round(time.time(), 3)
    with open(LOG, "a") as f:
        f.write(json.dumps(rec) + "\n")


def _file_response(flow, fx, path: pathlib.Path):
    data = path.read_bytes()
    route = fx["route"] if fx else {}
    ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    headers = {"Content-Type": ctype}
    if route.get("disposition"):
        headers["Content-Disposition"] = f'attachment; filename="{route["disposition"]}"'
    if flow.request.method == "HEAD":
        code = route.get("head", 200)
        resp = http.Response.make(code, b"", headers)
        if code == 200 and not route.get("chunked"):
            resp.headers["Content-Length"] = str(len(data))
        return resp
    rng = flow.request.headers.get("range", "")
    m = re.fullmatch(r"bytes=(\d+)-(\d*)", rng)
    if m and route.get("ranges", True) and not route.get("chunked"):
        a = int(m.group(1)); b = int(m.group(2)) if m.group(2) else len(data) - 1
        part = data[a:b + 1]
        resp = http.Response.make(206, part, {**headers, "Content-Range": f"bytes {a}-{a + len(part) - 1}/{len(data)}"})
    else:
        body = data
        if route.get("truncate"):
            body = data[: int(len(data) * route["truncate"])]
        resp = http.Response.make(200, body, headers)
        if route.get("truncate"):
            resp.headers["Content-Length"] = str(len(data))       # promises more than it sends
    if route.get("ranges", True) and not route.get("chunked"):
        resp.headers["Accept-Ranges"] = "bytes"
    if route.get("chunked"):
        resp.headers.pop("Content-Length", None)
        resp.headers["Transfer-Encoding"] = "chunked"
    return resp


async def _fault(flow, name):
    f = SPEC["faults"].get(name)
    if f is None:
        return False
    if f.get("reset"):
        flow.kill()
        return True
    if f.get("hang_seconds"):
        await asyncio.sleep(f["hang_seconds"])
        flow.response = http.Response.make(504, b"fixture world: hung on purpose")
        return True
    if f.get("bytes_per_second"):
        await asyncio.sleep(5)
        return False                                              # slow start, then the normal route
    if f.get("html"):
        flow.response = http.Response.make(200, b"<html><body>error page</body></html>", {"Content-Type": "text/html"})
        return True
    flow.response = http.Response.make(f["status"], f"fixture fault {name}".encode(), f.get("headers", {}))
    return True


def _match(host, path, query):
    for h, p, q, fx in ROUTES:
        route = fx["route"]
        if h != host:
            continue
        if route["kind"] == "dir":
            if path.startswith(route["prefix"]):
                return fx
        elif p == path and (q is None or (query or "").startswith(q)):
            return fx
    return None


async def request(flow: http.HTTPFlow) -> None:
    req = flow.request
    host, path, query = req.pretty_host, req.path.split("?", 1)[0], (req.path.split("?", 1) + [""])[1]
    h = req.headers
    fx = _match(host, path, query)
    _log({"kind": "request", "scheme": req.scheme, "host": host, "method": req.method, "path": req.path[:200],
          "fixture": fx["id"] if fx else None, "ua": h.get("user-agent", ""), "x_requested_with": h.get("x-requested-with", ""),
          "sec_fetch_mode": h.get("sec-fetch-mode", ""), "accept_encoding": h.get("accept-encoding", ""),
          "connection": h.get("connection", ""), "range": h.get("range", ""), "referer": h.get("referer", "")[:120],
          "cookie": "present" if h.get("cookie") else "", "header_order": list(h.keys())[:12],
          "fault_spec": _faults_now().get(fx["id"].split("#")[0]) if fx else None})
    fault = urllib.parse.parse_qs(query).get("fault", [None])[0]
    if not fault and fx:
        base_id = fx["id"].split("#")[0]
        spec = _faults_now().get(base_id)
        if spec:
            n = _control["count"][base_id] = _control["count"].get(base_id, 0) + 1
            name, _, start = spec.partition("@")
            if n >= int(start or 1):
                fault = name
    if fault and await _fault(flow, fault):
        return
    if fx is None:
        c = CASSETTE_INDEX.get((host, path))
        if c:
            body = (CASSETTES / c["id"] / "body.bin").read_bytes()
            flow.response = http.Response.make(c["status"], body, c.get("headers", {}))
            return
        flow.response = http.Response.make(404, b"fixture world: no route", {"Content-Type": "text/plain"})
        return
    route = fx["route"]
    kind = route["kind"]
    if route.get("require_cookie") and route["require_cookie"] not in h.get("cookie", ""):
        flow.response = http.Response.make(403, b"fixture world: cookie required")
        return
    if route.get("require_referer") and not h.get("referer", "").startswith(route["require_referer"]):
        flow.response = http.Response.make(403, b"fixture world: referer required")
        return
    if kind == "file":
        flow.response = _file_response(flow, fx, MEDIA / route["file"])
    elif kind == "dir":
        rel = path[len(route["prefix"]):] or "index"
        target = (MEDIA / route["dir"] / rel).resolve()
        if not str(target).startswith(str((MEDIA / route["dir"]).resolve())) or not target.is_file():
            flow.response = http.Response.make(404, b"fixture world: no such file")
        else:
            flow.response = _file_response(flow, None, target)
    elif kind == "redirect":
        chain = route["chain"]
        nxt = chain[0]
        flow.response = http.Response.make(302, b"", {"Location": nxt})
    elif kind == "html":
        flow.response = http.Response.make(200, route["body"].encode(), {"Content-Type": route.get("content_type", "text/html; charset=utf-8")})
    elif kind == "interstitial":
        if route["confirm_param"] in query:
            flow.response = _file_response(flow, fx, MEDIA / route["file"])
        else:
            u = fx["url"]
            page = (f'<!doctype html><html><head><title>Virus scan warning</title></head><body>'
                    f'<p>File is too large for a virus scan.</p>'
                    f'<a id="uc-download-link" href="{u.replace("&", "&amp;")}&amp;{route["confirm_param"]}">Download anyway</a>'
                    f'</body></html>')
            flow.response = http.Response.make(200, page.encode(), {"Content-Type": "text/html; charset=utf-8"})


# the redirect chain needs its intermediate hops as routes too
for fx in list(SPEC["fixtures"]):
    if fx["route"]["kind"] == "redirect":
        chain = fx["route"]["chain"]
        for i, hop in enumerate(chain[:-1]):
            h, p, q = _split(hop)
            ROUTES.append((h, p, None, {"id": f'{fx["id"]}#hop{i + 1}', "url": hop,
                                        "route": {"kind": "redirect", "chain": chain[i + 1:]}}))


def tls_failed_client(data: tls.TlsData) -> None:
    _log({"kind": "tls_failed_client", "sni": data.context.client.sni or "",
          "error": str(getattr(data.conn, "error", "") or "")[:200]})
