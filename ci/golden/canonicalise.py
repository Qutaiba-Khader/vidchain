#!/usr/bin/env python3
"""Turn a driver run (outcomes.json) + the fixture world's flow log into canonical golden records:
per scenario, the app's requests to the scenario's hosts (stack, method, host, path, ranged?) in order,
with consecutive repeats collapsed and long repeats counted, plus the outcome (front activity, user
actions taken, files with size + sha256, a few on-screen texts).
usage: canonicalise.py <outcomes.json> <flows.jsonl> <out.json>"""
import json
import re
import sys

STATE = re.compile(r"(^\d+_download\.(dat|json|jpg)$|^aio_.*\.(dat|json)$|^browsing_history\.|^user_profile\.|\.langcode$|^profileInstalled$)")
import urllib.parse

PKG = "org.websnake.vidchain"
outcomes = json.load(open(sys.argv[1]))["scenarios"]
flows = [json.loads(l) for l in open(sys.argv[2]) if l.strip()]


def stack(r):
    if r.get("x_requested_with") == PKG:
        return "webview"
    if r["ua"].startswith("okhttp/"):
        return "okhttp"
    if "Sec-Fetch-Mode" in r.get("header_order", []) and not r.get("x_requested_with"):
        return "yt-dlp"
    if r["ua"].startswith("stagefright") or r["ua"].startswith("Lavf"):
        return "media"
    if r["ua"].startswith("Dalvik"):
        return "urlconnection"
    return "other"


golden = {}
for sc in outcomes:
    host0 = urllib.parse.urlsplit(sc["url"]).hostname
    window = [r for r in flows if r["kind"] == "request" and sc["t_start"] - 1 <= r["t"] <= sc["t_end"] + 2
              and (r["host"].endswith("vidchain.test") or r["host"] == host0)]
    seq = []
    for r in window:
        key = f'{stack(r)} {r["method"]} {r["host"]}{r["path"].split("?")[0]}' + (" [range]" if r.get("range") else "")
        if seq and seq[-1][0] == key:
            seq[-1][1] += 1
        else:
            seq.append([key, 1])
    golden[sc["id"]] = {
        "url": sc["url"], "faults": sc["faults"],
        "requests": [k if n == 1 else (f"{k} x{n}" if n < 20 else f"{k} x20+") for k, n in seq],
        "request_set": sorted({k for k, _ in seq}),
        "outcome": {"front": sc["front_activity"].split("/")[-1].rstrip("}"), "actions": sc["actions"],
                    "files": [{"name": re.sub(r"^\d+_", "", f["path"].split("/")[-1]), "dir": "/".join(f["path"].split("/")[:-1]),
                               "bytes": f["bytes"], "sha256": f["sha256"]} for f in sc["files"]
                              if not STATE.search(f["path"].split("/")[-1]) and "crash_log" not in f["path"]],
                    "download_records": sum(1 for f in sc["files"] if re.search(r"/\d+_download\.json$", f["path"])),
                    "crash_logs": sum(1 for f in sc["files"] if "crash_log" in f["path"]),
                    "texts": [t for t in sc["texts_seen"] if len(t) < 80][:25]},
    }
json.dump(golden, open(sys.argv[3], "w"), indent=1, sort_keys=True)
print(f"{len(golden)} scenarios canonicalised -> {sys.argv[3]}")
