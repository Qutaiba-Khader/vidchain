#!/usr/bin/env python3
"""Records python/dukpy/golden.json with the REAL dukpy (Duktape): the shim must give the same answers.
usage (in a venv with `pip install dukpy`): dukpy_golden.py python/dukpy/players.json > python/dukpy/golden.json"""
import json
import sys

import dukpy

players = json.load(open(sys.argv[1]))
out = [{"name": p["name"], "code": p["code"], "kwargs": p.get("kwargs", {}), "result": dukpy.evaljs(p["code"], **p.get("kwargs", {}))} for p in players]
json.dump(out, sys.stdout, indent=1, ensure_ascii=False)
print()
