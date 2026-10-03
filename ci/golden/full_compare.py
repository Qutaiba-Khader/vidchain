#!/usr/bin/env python3
"""The one full test pass (T6.2): judge a finished app against the committed golden and fallback-expect.json.
  1. fallbacks OFF (golden scenarios): the app must behave exactly like the golden (seam inert):
     golden intersection <= requests <= golden union, same files (name, bytes, sha256), same actions, same crash logs.
  2. fallbacks ON (golden scenarios): the current method's requests and actions are still there; then each fixture's
     chain must end as fallback-expect.json says ("untouched" also keeps the golden's files byte-identical).
  3. fallbacks ON (fault scenarios): the current method's own files match the committed fault matrix; chains as expected.
usage: full_compare.py <golden.json> <fault-matrix.json> <expect.json> <off.json> <on.json> <on-outcomes.json>
                       <faults.json> <faults-outcomes.json> <report.md>"""
import json
import sys

golden, fmatrix, expect = (json.load(open(p)) for p in sys.argv[1:4])
off, on, faults = (json.load(open(p)) for p in (sys.argv[4], sys.argv[5], sys.argv[7]))
on_raw = {s["id"]: s for s in json.load(open(sys.argv[6]))["scenarios"]}
f_raw = {s["id"]: s for s in json.load(open(sys.argv[8]))["scenarios"]}
rows, bad = [], []


def files(rec):
    return [(f["name"], f["bytes"], f["sha256"]) for f in rec["outcome"]["files"]]


def judge(sid, raw, want):
    chain = raw.get("chain")
    got = {None: "untouched", "delivered": "delivered", "exhausted": "exhausted"}.get(chain, f"still {chain}")
    ok = got == want or (want == "either" and got in ("delivered", "exhausted"))
    tried = [l.split("method=")[1].split()[0] for l in raw.get("trace", []) if " event=attempt.end " in l and "method=" in l]
    return ok, got, tried


for sid, g in sorted(golden.items()):
    o = off.get(sid)
    if not o:
        bad.append(f"OFF {sid}: missing"); continue
    lo, hi = set(g["request_set_intersection"]), set(g["request_set_union"])
    probs = []
    if not lo <= set(o["request_set"]):
        probs.append("missing requests " + ", ".join(sorted(lo - set(o["request_set"]))))
    if not set(o["request_set"]) <= hi:
        probs.append("new requests " + ", ".join(sorted(set(o["request_set"]) - hi)))
    if files(o) != files(g):
        probs.append(f"files {files(o)} != golden {files(g)}")
    for k in ("actions", "crash_logs"):
        if o["outcome"][k] != g["outcome"][k]:
            probs.append(f"{k} {o['outcome'][k]} != golden {g['outcome'][k]}")
    rows.append(f"| OFF | {sid} | golden | {'identical' if not probs else '; '.join(probs)} | {'ok' if not probs else 'FAIL'} |")
    if probs:
        bad.append(f"OFF {sid}: " + "; ".join(probs))

for sid, g in sorted(golden.items()):
    o, raw, want = on.get(sid), on_raw.get(sid, {}), expect["golden"].get(sid, "untouched")
    if not o:
        bad.append(f"ON {sid}: missing"); continue
    probs = []
    if not set(g["request_set_intersection"]) <= set(o["request_set"]):
        probs.append("current method lost requests " + ", ".join(sorted(set(g["request_set_intersection"]) - set(o["request_set"]))))
    if o["outcome"]["actions"] != g["outcome"]["actions"]:
        probs.append(f"actions {o['outcome']['actions']} != golden {g['outcome']['actions']}")
    if o["outcome"]["crash_logs"] > g["outcome"]["crash_logs"]:          # the unchanged app's own crashes are inherited
        probs.append(f"{o['outcome']['crash_logs']} crash log(s), golden {g['outcome']['crash_logs']}")
    ok, got, tried = judge(sid, raw, want)
    if want == "untouched" and files(o) != files(g):
        probs.append(f"files {files(o)} != golden {files(g)}")
    if not ok:
        probs.append(f"chain {got}, expected {want}")
    rows.append(f"| ON | {sid} | {want} | {got}; tried {' '.join(tried) or '-'}{'; ' + '; '.join(probs) if probs else ''} | {'ok' if not probs else 'FAIL'} |")
    if probs:
        bad.append(f"ON {sid}: " + "; ".join(probs))

for sid, want in sorted(expect["faults"].items()):
    o, raw, fm = faults.get(sid), f_raw.get(sid, {}), fmatrix.get(sid)
    if not o or not fm:
        bad.append(f"FAULT {sid}: missing ({'run' if not o else 'fault matrix'})"); continue
    probs = []
    got_f, want_f = [(n, b) for n, b, _ in files(o)], [(n, b) for n, b, _ in files(fm)]
    if got_f != want_f:      # every fault persists, so no fallback may add a file: all files are the current method's
        probs.append(f"files {got_f} != fault matrix {want_f}")
    if o["outcome"]["crash_logs"] > fm["outcome"]["crash_logs"]:
        probs.append(f"{o['outcome']['crash_logs']} crash log(s), fault matrix {fm['outcome']['crash_logs']}")
    ok, got, tried = judge(sid, raw, want)
    if not ok:
        probs.append(f"chain {got}, expected {want}")
    rows.append(f"| FAULT | {sid} | {want} | {got}; tried {' '.join(tried) or '-'}{'; ' + '; '.join(probs) if probs else ''} | {'ok' if not probs else 'FAIL'} |")
    if probs:
        bad.append(f"FAULT {sid}: " + "; ".join(probs))

report = ("# Full test pass\n\n| pass | scenario | expected | result | verdict |\n|---|---|---|---|---|\n" + "\n".join(rows)
          + f"\n\n**{len(rows) - len(bad)} / {len(rows)} ok**\n")
open(sys.argv[9], "w").write(report)
print(report)
if bad:
    print("\n".join(bad))
    sys.exit(1)
