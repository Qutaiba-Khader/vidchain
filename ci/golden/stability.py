#!/usr/bin/env python3
"""Compare two golden passes of the same app and mark what is stable.
Stable = what the golden comparison (T1.6) may hold the app to: the set of (stack, method, host, path)
requests and the outcome (downloaded file names/bytes/sha256, crash logs, user actions). Anything that differs
between two runs of the SAME build is volatile and is recorded as such instead of becoming a false alarm.
usage: stability.py <pass1.json> <pass2.json> <out-golden.json>"""
import json
import sys

a, b = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
out, unstable = {}, []
for sid, ra in a.items():
    rb = b.get(sid, {})
    fa = [(f["name"], f["bytes"], f["sha256"]) for f in ra["outcome"]["files"]]
    fb = [(f["name"], f["bytes"], f["sha256"]) for f in rb.get("outcome", {}).get("files", [])]
    stable = {
        "request_set": ra["request_set"] == rb.get("request_set"),
        "files": fa == fb,
        "actions": ra["outcome"]["actions"] == rb.get("outcome", {}).get("actions"),
        "crash_logs": ra["outcome"]["crash_logs"] == rb.get("outcome", {}).get("crash_logs"),
    }
    out[sid] = {**ra, "stable": stable,
                "request_set_union": sorted(set(ra["request_set"]) | set(rb.get("request_set", []))),
                "request_set_intersection": sorted(set(ra["request_set"]) & set(rb.get("request_set", [])))}
    if not all(stable.values()):
        unstable.append(f"{sid}: " + ", ".join(k for k, v in stable.items() if not v))
json.dump(out, open(sys.argv[3], "w"), indent=1, sort_keys=True)
print(f"{len(out)} scenarios, {len(out) - len(unstable)} fully stable")
for u in unstable:
    print("  volatile:", u)
