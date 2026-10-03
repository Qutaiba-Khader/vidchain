#!/usr/bin/env python3
"""Live canary (T6.1, Q12 = A): a few openly licensed or public probes from the runner's own network.
Each probe ends PASS, BLOCKED (the site refused a datacenter IP: 403/429/bot check - not a failure) or FAIL.
Writes canary-results.json and a step summary; prints fail=<n> to $GITHUB_OUTPUT. Never fails the job itself.
usage: canary.py <out-dir> [--seed-failure]"""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request

OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
SEED = "--seed-failure" in sys.argv
UA = "VidChain-canary/1 (+https://github.com/Qutaiba-Khader/vidchain)"
BLOCK_TEXT = re.compile(r"Sign in to confirm|not a bot|captcha|HTTP Error 4(03|29|51)|rate.?limit|Too Many Requests", re.I)
COMMONS_FILE = "File:Big_Buck_Bunny_4K.webm"                      # CC BY 3.0, Blender Foundation
YOUTUBE_CC = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"          # Big Buck Bunny, Blender Foundation, CC BY
NIGHTLY = "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases"  # what method N fetches on the phone


def get(url, headers=None, limit=64 << 20, redirect=True):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    opener = urllib.request.build_opener() if redirect else urllib.request.build_opener(NoRedirect)
    with opener.open(req, timeout=60) as r:
        return r.status, dict(r.headers), r.read(limit)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def http_verdict(e):
    return ("BLOCKED", f"HTTP {e.code}") if e.code in (403, 429, 451) else ("FAIL", f"HTTP {e.code}")


def commons_direct():
    api = ("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url&titles="
           + urllib.request.quote(COMMONS_FILE))
    url = next(iter(json.loads(get(api)[2])["query"]["pages"].values()))["imageinfo"][0]["url"]
    status, _, body = get(url, {"Range": "bytes=0-65535"}, limit=65536)
    if body[:4] != b"\x1a\x45\xdf\xa3":
        return "FAIL", f"HTTP {status}, not WebM/EBML ({body[:4].hex()})"
    return "PASS", f"HTTP {status}, {len(body)} bytes of WebM"


def ytdlp(url):
    p = subprocess.run(["yt-dlp", "--no-warnings", "--js-runtimes", "node", "--skip-download", "-J", "--", url], capture_output=True, text=True, timeout=180)
    if p.returncode == 0:
        info = json.loads(p.stdout)
        n = len(info.get("formats") or [info])
        return ("PASS", f"{n} format(s)") if n else ("FAIL", "no formats")
    err = (p.stderr.strip().splitlines() or ["exit %d" % p.returncode])[-1][:200]
    return ("BLOCKED" if BLOCK_TEXT.search(p.stderr) else "FAIL"), err


def nightly():
    try:
        status, h, _ = get(f"{NIGHTLY}/latest", redirect=False, limit=1)
    except urllib.error.HTTPError as e:      # the redirect itself is the answer (as on the phone, no following)
        if e.code not in (301, 302, 303, 307, 308):
            raise
        status, h = e.code, dict(e.headers)
    loc = h.get("Location") or h.get("location") or ""
    version = loc.rsplit("/tag/", 1)[-1] if "/tag/" in loc else ""
    if not re.fullmatch(r"[0-9A-Za-z._-]{4,64}", version):
        return "FAIL", f"no version in the latest redirect (HTTP {status})"
    sums = get(f"{NIGHTLY}/download/{version}/SHA2-256SUMS", limit=1 << 20)[2].decode()
    want = next((l.split()[0].lower() for l in sums.splitlines() if len(l.split()) >= 2 and l.split()[1].lstrip("*") == "yt-dlp"), None)
    if not want:
        return "FAIL", f"{version}: SHA2-256SUMS has no yt-dlp line"
    got = hashlib.sha256(get(f"{NIGHTLY}/download/{version}/yt-dlp")[2]).hexdigest()
    return ("PASS", f"{version}: checksum matches") if got == want else ("FAIL", f"{version}: checksum mismatch")


PROBES = [
    ("commons-direct", "Wikimedia Commons CC file, range request (O/A/D path)", commons_direct),
    ("ytdlp-commons", "yt-dlp on the Commons file page (Y path)", lambda: ytdlp("https://commons.wikimedia.org/wiki/" + COMMONS_FILE)),
    ("ytdlp-youtube-cc", "yt-dlp on a CC BY YouTube video (B1 chain)", lambda: ytdlp(YOUTUBE_CC)),
    ("nightly-ytdlp", "nightly yt-dlp URL + SHA2-256SUMS (method N)", nightly),
]
if SEED:
    PROBES.append(("seeded-failure", "seeded failure (workflow_dispatch input)", lambda: ("FAIL", "seeded on purpose")))

results = []
for pid, what, fn in PROBES:
    try:
        verdict, detail = fn()
    except urllib.error.HTTPError as e:
        verdict, detail = http_verdict(e)
    except Exception as e:  # noqa: BLE001 - every probe ends with a verdict
        verdict, detail = "FAIL", f"{type(e).__name__}: {str(e)[:160]}"
    results.append({"id": pid, "what": what, "verdict": verdict, "detail": detail})
    print(f"{verdict:8} {pid}: {detail}")

(OUT / "canary-results.json").write_text(json.dumps(results, indent=2) + "\n")
fails = sum(r["verdict"] == "FAIL" for r in results)
table = "| probe | verdict | detail |\n|---|---|---|\n" + "".join(f"| {r['id']} | {r['verdict']} | {r['detail']} |\n" for r in results)
(OUT / "canary-summary.md").write_text(table)
for var, text in (("GITHUB_STEP_SUMMARY", "### Live canary\n\n" + table), ("GITHUB_OUTPUT", f"fail={fails}\n")):
    if os.environ.get(var):
        with open(os.environ[var], "a") as f:
            f.write(text)
