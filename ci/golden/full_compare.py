#!/usr/bin/env python3
"""The one full test pass (T6.2, T9): judge a finished app against the committed golden and fallback-expect.json.
  1. golden scenarios: each fixture must end as fallback-expect.json says ("untouched" = the file the original app
     made, same name and bytes). Since T9 (owner, 2026-10-14) there is no fallbacks-off mode and the app's requests
     are no longer compared with the original's: only what the user gets is judged.
  2. fault scenarios: no fallback may add a media file while the fault persists; chains end as expected.
usage: full_compare.py <golden.json> <fault-matrix.json> <expect.json> <on.json> <on-outcomes.json>
                       <faults.json> <faults-outcomes.json> <report.md>"""
import json
import sys

golden, fmatrix, expect = (json.load(open(p)) for p in sys.argv[1:4])
on, faults = (json.load(open(p)) for p in (sys.argv[4], sys.argv[6]))
on_raw = {s["id"]: s for s in json.load(open(sys.argv[5]))["scenarios"]}
f_raw = {s["id"]: s for s in json.load(open(sys.argv[7]))["scenarios"]}
REPORT = sys.argv[8]
rows, bad = [], []


def files(rec):
    """(name, bytes): the fixture media are regenerated on every run (encoders are not bit-reproducible), so no hash"""
    return [(f["name"], f["bytes"]) for f in rec["outcome"]["files"]]


def crash(raw):
    t = " ".join(" / ".join(raw.get("crash_texts", [])).split())
    return (t[:400] or "(no text captured)").replace("|", "/")


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


for sid, g in sorted(golden.items()):
    o, raw, want = on.get(sid), on_raw.get(sid, {}), expect["golden"].get(sid, "untouched")
    if not o:
        bad.append(f"ON {sid}: missing"); continue
    probs = []
    if o["outcome"]["crash_logs"] > g["outcome"]["crash_logs"]:          # the unchanged app's own crashes are inherited
        probs.append(f"{o['outcome']['crash_logs']} crash log(s), golden {g['outcome']['crash_logs']}: " + crash(raw))
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
    base_crashes = fm["outcome"]["crash_logs"]
    # the count is judged (owner rule, INBOX #6: no failing scenario relabelled without the owner); texts go in the report
    if o["outcome"]["crash_logs"] > base_crashes:
        probs.append(f"{o['outcome']['crash_logs']} crash log(s), fault matrix {base_crashes}: " + crash(raw))
    ok, got, tried = judge(raw, o, fm, want)
    if not ok:
        probs.append(f"chain {got}, expected {want}")
    rows.append(f"| FAULT | {sid} | {want} | {got}; tried {' '.join(tried) or '-'}{'; ' + '; '.join(probs) if probs else ''} | {'ok' if not probs else 'FAIL'} |")
    if probs:
        bad.append(f"FAULT {sid}: " + "; ".join(probs))

report = ("# Full test pass\n\n| pass | scenario | expected | result | verdict |\n|---|---|---|---|---|\n" + "\n".join(rows)
          + f"\n\n**{len(rows) - len(bad)} / {len(rows)} ok**\n")
open(REPORT, "w").write(report)
print(report)
if bad:
    print("\n".join(bad))
    sys.exit(1)
