#!/usr/bin/env python3
"""Check a release's files, the Obtainium entry and every link in README.md.
usage: GH_TOKEN=... check_links.py README.md [--tag TAG]
--tag TAG checks that release (drafts included) instead of the latest one - used on the DRAFT before
publishing, so a broken release is never made public. Exit 1 on any failure."""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import obtainium  # noqa: E402

args = sys.argv[1:]
TAG = None
if "--tag" in args:
    i = args.index("--tag")
    TAG = args[i + 1]
    del args[i:i + 2]
README = args[0] if args else "README.md"
TOKEN = os.environ.get("GH_TOKEN", "")
REPO = obtainium.REPO
API = f"https://api.github.com/repos/{REPO}"
ASSETS = obtainium.CONTRACT["assets"]
failures = []


def check(ok, msg):
    print(("  ok   " if ok else "  FAIL ") + msg)
    if not ok:
        failures.append(msg)


def fetch(url, auth=False, accept=None, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "vidchain-link-check"})
    if auth and TOKEN:
        req.add_header("Authorization", f"token {TOKEN}")
    if accept:
        req.add_header("Accept", accept)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read(300_000) if method == "GET" else b""
    except urllib.error.HTTPError as e:
        return e.code, e.read(2000)
    except Exception as e:  # network error: report, don't crash
        return 0, str(e).encode()


print("Release")
if TAG:
    st, body = fetch(f"{API}/releases?per_page=30", auth=True)
    release = next((r for r in (json.loads(body) if st == 200 else []) if r["tag_name"] == TAG), {})
    check(bool(release), f"release {TAG} found (HTTP {st})")
else:
    st, body = fetch(f"{API}/releases/latest", auth=True)
    release = json.loads(body) if st == 200 else {}
    check(st == 200, f"latest release readable (HTTP {st})")
tag = release.get("tag_name", "")
assets = {a["name"]: a for a in release.get("assets", [])}
for name in ASSETS.values():
    check(name in assets, f"asset {name} present")
for name, a in sorted(assets.items()):
    if name.endswith(".apk"):
        st, _ = fetch(a["url"], auth=True, accept="application/octet-stream", method="HEAD")
        check(st == 200 and a["size"] > 10_000_000, f"{name} downloads ({a['size'] // 1_000_000} MB, HTTP {st})")
check(any(n.startswith("SOURCES-") for n in assets), "complete corresponding source attached (SOURCES-*)")
check("bill-of-lading.json" in assets, "bill of lading attached")
check(re.fullmatch(r"v\d+\.\d+\.\d+|v0\.0\.0-dryrun\.\d+", tag) is not None, f"tag = v<app version> ({tag})")

print("Obtainium entry")
cfg = obtainium.CONFIG
settings = json.loads(cfg["additionalSettings"])
check(cfg["url"] == f"https://github.com/{REPO}", "points at this repo")
check(cfg["id"] == "org.websnake.vidchain", "app id is the built package")
check(settings.get("autoApkFilterByArch") is True, "picks the APK for the phone's CPU")
check(settings.get("includePrereleases") is False, "ignores pre-releases")
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    hits = [n for n in assets if abi in n and n.endswith(".apk")]
    check(len(hits) == 1, f"exactly one APK matches a {abi} phone ({hits})")
round_trip = urllib.parse.unquote(urllib.parse.unquote(obtainium.add_url().split("?r=", 1)[1]))[len("obtainium://app/"):]
check(json.loads(round_trip) == cfg, "add link decodes back to the config")
st, body = fetch(obtainium.add_url())
check(st == 200 and b"Invalid URL" not in body and b"obtainium://app/%7B" in body,
      f"Obtainium redirect page accepts the link and offers obtainium://app/ (HTTP {st})")

print("README links")
text = open(README, encoding="utf-8").read()
check(obtainium.add_url() in text, "README carries the current Obtainium add link")
for url in sorted(set(re.findall(r"\]\((https?://[^)\s]+)\)", text))):
    if url == obtainium.add_url():
        continue
    m = re.match(rf"https://github\.com/{REPO}/releases/latest/download/(.+)$", url)
    if m:
        check(m.group(1) in assets, f"release has {m.group(1)}")
        continue
    if url.startswith(f"https://github.com/{REPO}"):
        path = url[len(f"https://github.com/{REPO}"):]
        if path in ("", "/"):
            st = fetch(API, auth=True)[0]
        elif path in ("/releases", "/releases/latest"):
            st = fetch(f"{API}/releases?per_page=1", auth=True)[0]
        elif m2 := re.match(r"/actions/workflows/([^/]+?)(?:/badge\.svg)?$", path):
            st = fetch(f"{API}/actions/workflows/{m2.group(1)}", auth=True)[0]
        elif m2 := re.match(r"/blob/main/(.+)$", path):
            st = fetch(f"{API}/contents/{m2.group(1)}", auth=True)[0]
        elif path == "/issues" or path.startswith("/issues/"):
            st = fetch(f"{API}/issues?per_page=1", auth=True)[0]
        else:
            st = 0
        check(st == 200, f"{url} (HTTP {st})")
        continue
    st = fetch(url)[0]
    check(st == 200, f"{url} (HTTP {st})")

print(f"\n{'FAILED' if failures else 'PASSED'}: {len(failures)} failure(s)")
sys.exit(1 if failures else 0)
