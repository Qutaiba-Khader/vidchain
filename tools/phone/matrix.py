#!/usr/bin/env python3
"""T9 stage 4c: the site x method test matrix on the USB test phone (run on CT 200).

For every site link below and every VidChain method, the app runs that one method on that one link (MatrixReceiver,
reachable from `adb shell` only) and the result is read back from the app's trace: ok / failed / unsupported /
not-media / timeout, and the time it took. One method at a time, so the times are clean.

  matrix.py run [out.jsonl]          run the matrix (about an hour); appends one JSON line per (site, method)
  matrix.py seed <out.jsonl>         print the seed order (Kotlin map for race/SeedOrder.kt) and a table

The seed order per site: the methods that worked, fastest first, then the others in the chain's order. A site with
no working method is reported (UPSTREAM if the site itself refuses, e.g. a login wall)."""
import json
import re
import subprocess
import sys
import time

SERIAL = "R58M463R4JP"
PKG = "org.websnake.vidchain"
RECEIVER = f"{PKG}/org.websnake.vidchain.fallback.ui.MatrixReceiver"
TRACE = f"/data/data/{PKG}/files/trace/trace.log"
METHODS = ["C", "S", "Y", "P", "F", "N", "W", "R", "L", "H", "G", "U", "T", "X", "O", "A", "D", "M"]
SITES = {
    "youtube.com": "https://www.youtube.com/watch?v=jNQXAC9IVRw",
    "x.com": "https://x.com/RAVANOUS100/status/2110045035546746885",
    "instagram.com": "https://www.instagram.com/p/aye83DjauH/",
    "facebook.com": "https://www.facebook.com/radiokicksfm/videos/3676516585958356/",
    "tiktok.com": "https://www.tiktok.com/@scout2015/video/6718335390845095173",
    "reddit.com": "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/",
    "dailymotion.com": "https://www.dailymotion.com/video/x5kesuj",
    "vimeo.com": "https://vimeo.com/76979871",
    "soundcloud.com": "https://soundcloud.com/ethmusic/lostin-powers-she-so-heavy",
    "ted.com": "https://www.ted.com/talks/dan_dennett_on_our_consciousness",
    "pinterest.com": "https://www.pinterest.com/pin/664281013778109217/",
    "*": "https://rclone-public.websnake.org/storage/vidchain-test/screens/clip.mp4",
}


def sh(cmd, timeout=60):
    return subprocess.run(["adb", "-s", SERIAL, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout


def trace_lines():
    return sh(f"su -c 'grep event=matrix {TRACE}'").splitlines()


def one(site, url, method, run):
    sh(f"am broadcast -a org.websnake.vidchain.MATRIX -n {RECEIVER} --es url '{url}' --es method {method} --es run {run} >/dev/null")
    deadline = time.time() + 200
    while time.time() < deadline:
        time.sleep(3)
        for l in trace_lines():
            if f"run={run} " in l or f'run={run}"' in l:
                m = re.search(r"result=(\S+).*?dur=(\d+)ms", l) or re.search(r"result=(\S+)", l)
                why = (re.search(r'reason="([^"]*)"', l) or [None, ""])[1]
                return {"site": site, "method": method, "result": m.group(1), "ms": int(m.group(2)) if m and m.lastindex == 2 else None,
                        "why": why.split(" ", 2)[-1][:200]}
    return {"site": site, "method": method, "result": "no-answer", "ms": None, "why": "no trace line in 200 s"}


def run(out):
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    time.sleep(10)
    sh("input keyevent KEYCODE_HOME")
    with open(out, "a") as f:
        for site, url in SITES.items():
            for m in METHODS:
                r = one(site, url, m, f"{int(time.time())}{m}")
                print(json.dumps(r), flush=True)
                f.write(json.dumps(r) + "\n"); f.flush()


def seed(path):
    rows = [json.loads(l) for l in open(path) if l.strip()]
    by = {}
    for r in rows:
        by.setdefault(r["site"], []).append(r)
    out = []
    for site, rs in by.items():
        ok = sorted((r for r in rs if r["result"] == "ok"), key=lambda r: r["ms"] or 1e9)
        order = [r["method"] for r in ok] + [m for m in METHODS if m not in {r["method"] for r in ok}]
        out.append((site, order, ok))
        print(f"{site:16} works: {', '.join(f'{r['method']} {r['ms'] / 1000:.1f}s' for r in ok) or 'NOTHING'}")
    print("\n\tval SEEDS: Map<String, List<String>> = mapOf(")
    for site, order, ok in out:
        print(f'\t\t"{site}" to listOf({", ".join(chr(34) + m + chr(34) for m in order)}),')
    print("\t)")


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    if a[0] == "run":
        run(a[1] if len(a) > 1 else "/root/build/phone/matrix.jsonl")
    elif a[0] == "seed":
        seed(a[1])
    else:
        sys.exit(__doc__)
