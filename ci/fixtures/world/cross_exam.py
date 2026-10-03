#!/usr/bin/env python3
"""Cross-examine the fixture world with independent desktop reference tools (curl, ffprobe, yt-dlp, node)
BEFORE any emulator test relies on it (T1.2): a fixture whose expected outcome is not confirmed by a tool
the app does not share is not a fixture - it would be a self-fulfilling oracle.
usage: cross_exam.py <ca.pem> [report.json]      proxy: http://127.0.0.1:8080 (world.py running)"""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
SPEC = json.loads((HERE / "fixtures.json").read_text())
CA = sys.argv[1]
REPORT = sys.argv[2] if len(sys.argv) > 2 else "cross-exam.json"
PROXY = "http://127.0.0.1:8080"
ENV = {**os.environ, "http_proxy": PROXY, "https_proxy": PROXY, "HTTP_PROXY": PROXY, "HTTPS_PROXY": PROXY,
       "SSL_CERT_FILE": CA, "REQUESTS_CA_BUNDLE": CA}
results, failures = [], 0


def run(cmd, timeout=60):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=ENV)
    return p.returncode, p.stdout, p.stderr


def curl(*args, timeout=40):
    return run(["curl", "-sS", "--proxy", PROXY, "--cacert", CA, "--max-time", "30", *args], timeout=timeout)


def ffprobe(url, what):
    return run(["ffprobe", "-v", "error", "-show_entries", what, "-of", "csv=p=0", url], timeout=90)


def check(fid, name, ok, detail=""):
    global failures
    results.append({"fixture": fid, "check": name, "ok": bool(ok), "detail": str(detail)[:200]})
    print(("  ok   " if ok else "  FAIL ") + f"{fid}: {name} {detail if not ok else ''}".rstrip())
    failures += 0 if ok else 1


for fx in SPEC["fixtures"]:
    fid, url, ex = fx["id"], fx["url"], fx["expect"]
    if "curl_head" in ex:
        rc, out, _ = curl("-o", "/dev/null", "-w", "%{http_code}", "-I", url)
        check(fid, f"HEAD -> {ex['curl_head']}", out.strip() == str(ex["curl_head"]), out)
    if "content_length" in ex or "content_disposition" in ex:
        rc, out, _ = curl("-D", "-", "-o", "/dev/null", url)
        hdr = out.lower()
        if "content_length" in ex:
            check(fid, f"Content-Length {'present' if ex['content_length'] else 'absent'}", ("content-length:" in hdr) == ex["content_length"], hdr[:150])
        if "content_disposition" in ex:
            check(fid, "Content-Disposition filename", f'filename="{ex["content_disposition"]}"' in out, hdr[:150])
    if "ffprobe_duration" in ex:
        rc, out, err = ffprobe(url, "format=duration")
        d = float(out.strip().splitlines()[0]) if rc == 0 and out.strip() else -1
        check(fid, f"ffprobe duration ~{ex['ffprobe_duration']}s", abs(d - ex["ffprobe_duration"]) < 1.0, f"rc={rc} d={d} {err[:120]}")
    if "ffprobe_streams" in ex:
        rc, out, err = ffprobe(url, "stream=codec_type")
        got = sorted({l.strip() for l in out.splitlines() if l.strip()})
        check(fid, f"ffprobe streams {ex['ffprobe_streams']}", got == sorted(ex["ffprobe_streams"]), f"rc={rc} {got} {err[:100]}")
    if "ffprobe_fails" in ex:
        rc, out, err = ffprobe(url, "format=duration")
        check(fid, "ffprobe rejects it (trap)", rc != 0, f"rc={rc}")
    if "curl_error" in ex:
        rc, out, err = curl("-o", "/dev/null", "--max-time", "10", url, timeout=20)
        check(fid, "download fails (trap)", rc != 0, f"rc={rc}")
    if "redirects" in ex:
        rc, out, _ = curl("-L", "-o", "/dev/null", "-w", "%{num_redirects} %{url_effective}", url)
        n, final = (out.split() + ["", ""])[:2]
        check(fid, f"{ex['redirects']} redirects to the file", n == str(ex["redirects"]) and final == ex["final_url"], out)
    if "html_contains" in ex or "html_lacks" in ex or "js_eval_url" in ex:
        rc, body, _ = curl(url)
        if "html_contains" in ex:
            check(fid, "page contains the link", ex["html_contains"] in body, body[:120])
        if "html_lacks" in ex:
            check(fid, "page has no static link (JS only)", ex["html_lacks"] not in body)
        if "js_eval_url" in ex:
            script = re.search(r"<script>(.*?)</script>", body, re.S).group(1)
            js = ("var el={};var document={getElementById:function(){return el;}};" + script + ";console.log(el.src);")
            rc2, out2, err2 = run(["node", "-e", js])
            check(fid, "a JS engine reveals the URL", out2.strip() == ex["js_eval_url"], out2 + err2[:100])
    if "status_without" in ex:
        rc, out, _ = curl("-o", "/dev/null", "-w", "%{http_code}", url)
        check(fid, f"without credentials -> {ex['status_without']}", out.strip() == str(ex["status_without"]), out)
        extra = []
        if "cookie" in ex["with"]:
            extra += ["-H", f"Cookie: {ex['with']['cookie']}"]
        if "referer" in ex["with"]:
            extra += ["-H", f"Referer: {ex['with']['referer']}"]
        rc, out, _ = curl(*extra, "-o", "/dev/null", "-w", "%{http_code}", url)
        check(fid, f"with credentials -> {ex['status_with']}", out.strip() == str(ex["status_with"]), out)
    if "confirmed_url" in ex:
        rc, out, err = ffprobe(ex["confirmed_url"], "format=duration")
        d = float(out.strip()) if rc == 0 and out.strip() else -1
        check(fid, "confirm link serves the file", abs(d - ex["ffprobe_confirmed"]) < 1.0, f"rc={rc} d={d}")
    if "ytdlp_url" in ex:
        rc, out, err = run(["yt-dlp", "--proxy", PROXY, "--force-generic-extractor", "-g", "--no-warnings", url], timeout=120)
        got = out.strip().splitlines()[0] if out.strip() else ""
        check(fid, "yt-dlp generic extractor finds the media", got == ex["ytdlp_url"], f"rc={rc} got={got} {err[-160:]}")
    if "playlist_contains" in ex:
        rc, body, _ = curl(url)
        check(fid, f"playlist has {ex['playlist_contains']}", ex["playlist_contains"] in body)

print("faults")
base = "https://files.vidchain.test/media/clip.mp4"
rc, out, _ = curl("-D", "-", "-o", "/dev/null", base + "?fault=429")
status = [l for l in out.splitlines() if l.startswith("HTTP/") and "Connection established" not in l]
check("fault-429", "429 with Retry-After", bool(status) and " 429" in status[-1] and "retry-after: 2" in out.lower(), out[:80])
rc, out, _ = curl("-o", "/dev/null", "-w", "%{http_code}", base + "?fault=500")
check("fault-500", "500", out.strip() == "500", out)
rc, out, _ = curl("-o", "/dev/null", base + "?fault=reset")
check("fault-reset", "connection reset", rc != 0, f"rc={rc}")
rc, out, _ = curl("-o", "/dev/null", "--max-time", "5", base + "?fault=hang", timeout=15)
check("fault-hang", "hangs past the client timeout", rc == 28, f"rc={rc}")
rc, body, _ = curl(base + "?fault=html")
check("fault-html", "error page instead of the file", "<html>" in body)

manifest = HERE.parent / "cassettes" / "manifest.json"
if manifest.exists():
    print("cassettes")
    for c in json.loads(manifest.read_text())["cassettes"]:
        rc, _, _ = curl("-o", "/tmp/cassette.bin", c["url"])
        h = hashlib.sha256(open("/tmp/cassette.bin", "rb").read()).hexdigest() if rc == 0 else ""
        check(c["id"], "replayed body matches the recorded sha256", h == c["sha256"], h[:16])

json.dump({"results": results, "failures": failures}, open(REPORT, "w"), indent=1)
total = len(results)
print(f"\n{total - failures}/{total} checks passed")
if total < 35:
    print(f"FAIL: only {total} checks ran - the cross-examination itself is broken"); sys.exit(1)
sys.exit(1 if failures else 0)
