#!/usr/bin/env python3
"""Natives reproducibility (T6.1): a fresh build must give byte-identical outputs to the ones natives.lock pins.
usage: repro.py <name> <dir with <abi>/<file>>   (prints one line per output; exit 1 on any difference)"""
import hashlib
import json
import pathlib
import sys

name, d = sys.argv[1], pathlib.Path(sys.argv[2])
lock = json.loads((pathlib.Path(__file__).resolve().parents[1] / "natives.lock").read_text())
bad = 0
for o in next(n for n in lock["natives"] if n["name"] == name)["outputs"]:
    f = d / o["abi"] / o["file"]
    got = hashlib.sha256(f.read_bytes()).hexdigest() if f.is_file() else "missing"
    same = got == o["sha256"]
    bad += not same
    print(f"{'SAME' if same else 'DIFFERENT'} {name} {o['abi']}: built {got[:16]}, natives.lock {o['sha256'][:16]}")
sys.exit(1 if bad else 0)
