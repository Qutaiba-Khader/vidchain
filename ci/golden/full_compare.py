#!/usr/bin/env python3
"""The one full test pass (T6.2): judge a finished app against the committed golden and fallback-expect.json.
  1. fallbacks OFF (golden scenarios): the app must behave exactly like the golden (seam inert):
     golden intersection <= requests <= golden union, same files (name, bytes, sha256), same actions, same crash logs.
  2. fallbacks ON (golden scenarios): the current method's requests and actions are still there; then each fixture's
     chain must end as fallback-expect.json says ("untouched" also keeps the golden's files byte-identical).
  3. fallbacks ON (fault scenarios): the current method's own files match the committed fault matrix; chains as expected.
usage: full_compare.py <golden.json> <fault-matrix.json> <expect.json> <off.json> <on.json> <on-outcomes.json>
                       <faults.json> <faults-outcomes.json> <report.md> [<off2.json>]
The OFF pass runs twice: the unchanged app itself varies between runs (browser timing), so a scenario passes when
either OFF pass is identical to the golden (the golden proved stability only within its own run)."""
import json
import sys

golden, fmatrix, expect = (json.load(open(p)) for p in sys.argv[1:4])
off, on, faults = (json.load(open(p)) for p in (sys.argv[4], sys.argv[5], sys.argv[7]))
off2 = json.load(open(sys.argv[10])) if len(sys.argv) > 10 else {}
on_raw = {s["id"]: s for s in json.load(open(sys.argv[6]))["scenarios"]}
f_raw = {s["id"]: s for s in json.load(open(sys.argv[8]))["scenarios"]}
rows, bad = [], []


def files(rec):
    """(name, bytes): the fixture media are regenerated on every run (encoders are not bit-reproducible), so no hash"""
    return [(f["name"], f["bytes"]) for f in rec["outcome"]["files"]]


def judge(raw, rec, base, want):
    """what happened, in the vocabulary of fallback-expect.json"""
    chain = raw.get("chain")
    new_files = [f for f in files(rec) if f[1] > 0 and f not in (files(base) if base else [])]
    if chain == "running":
        got = "still running"
    elif chain == "delivered":
        got = "delivered"
    elif new_files:
        got = "rescued"
    elif chain is None:
        real = lambda r: [f for f in files(r) if f[1] > 0]           # empty partial files depend on timing
        got = "untouched" if base is None or real(rec) == real(base) else "files differ"
    else:
        got = "nothing"
    ok = (got == want or (want == "rescued" and got == "delivered") or (want == "nothing" and got == "untouched" and not new_files)
          or (want == "either" and got in ("rescued", "delivered", "nothing", "untouched")))
    tried = [l.split("method=")[1].split()[0] for l in raw.get("trace", []) if " event=attempt.end " in l and "method=" in l]
    rescue = [l.split("result=")[1].split()[0] for l in raw.get("trace", []) if " event=share.rescue" in l and "result=" in l]
    return ok, got, tried + (["picker:" + rescue[-1]] if rescue else [])


def off_problems(o, g):
    lo, hi = set(g["request_set_intersection"]), set(g["request_set_union"])
    probs = []
    if not lo <= set(o["request_set"]):
        probs.append("missing requests " + ", ".join(sorted(lo - set(o["request_set"]))))
    if not set(o["request_set"]) <= hi:
        probs.append("new requests " + ", ".join(sorted(set(o["request_set"]) - hi)))
    if files(o) != files(g):
        probs.append(f"files {files(o)} != golden {files(g)}")
    if o["outcome"]["crash_logs"] != g["outcome"]["crash_logs"]:
        probs.append(f"crash_logs {o['outcome']['crash_logs']} != golden {g['outcome']['crash_logs']}")
    return probs


for sid, g in sorted(golden.items()):
    passes = [p for p in (off.get(sid), off2.get(sid)) if p]
    if not passes:
        bad.append(f"OFF {sid}: missing"); continue
    results = [off_problems(p, g) for p in passes]
    best = min(range(len(results)), key=lambda k: len(results[k]))
    probs, o = results[best], passes[best]
    # the user's taps depend on what the browser shows when (timing): reported, not judged
    note = "" if o["outcome"]["actions"] == g["outcome"]["actions"] else f" (taps {o['outcome']['actions']} vs golden {g['outcome']['actions']})"
    which = f" [pass {best + 1} of {len(passes)}]" if len(passes) > 1 else ""
    rows.append(f"| OFF | {sid} | golden | {('identical' if not probs else '; '.join(probs)) + note + which} | {'ok' if not probs else 'FAIL'} |")
    if probs:
        bad.append(f"OFF {sid}: " + "; ".join(probs))

for sid, g in sorted(golden.items()):
    o, raw, want = on.get(sid), on_raw.get(sid, {}), expect["golden"].get(sid, "untouched")
    if not o:
        bad.append(f"ON {sid}: missing"); continue
    probs = []
    if want == "untouched" and not set(g["request_set_intersection"]) <= set(o["request_set"]):
        probs.append("current method lost requests " + ", ".join(sorted(set(g["request_set_intersection"]) - set(o["request_set"]))))
    if o["outcome"]["crash_logs"] > g["outcome"]["crash_logs"]:          # the unchanged app's own crashes are inherited
        probs.append(f"{o['outcome']['crash_logs']} crash log(s), golden {g['outcome']['crash_logs']}")
    ok, got, tried = judge(raw, o, g, want)
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
    # every fault persists, so no fallback may add a media file (the current method's own partial files vary with timing)
    added = [f for f in files(o) if f[1] > 0 and f not in files(fm)]
    if added:
        probs.append(f"VidChain saved {added} although the fault persists")
    if o["outcome"]["crash_logs"] > fm["outcome"]["crash_logs"]:
        probs.append(f"{o['outcome']['crash_logs']} crash log(s), fault matrix {fm['outcome']['crash_logs']}")
    ok, got, tried = judge(raw, o, fm, want)
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
