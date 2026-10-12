#!/usr/bin/env python3
"""T8.11: extract the media probe's JavaScript from ProbeScript.kt and run tools/probe_test.js on it with node.
Fails when the script does not parse or a detection case is missed. usage: tools/probe_check.py"""
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "engines/engine-web/src/main/java/org/websnake/vidchain/web/probe/ProbeScript.kt"
t = SRC.read_text()
start = t.index('private val JS = """') + len('private val JS = """')
js = t[start:t.rindex('""".trimIndent()')].replace("$NAME", "VidChainProbe").replace("__AUTOPLAY__", "false")
with tempfile.TemporaryDirectory() as d:
    f = pathlib.Path(d) / "probe.js"
    f.write_text(js)
    for cmd in (["node", "--check", str(f)], ["node", str(ROOT / "tools/probe_test.js"), str(f)]):
        r = subprocess.run(cmd, capture_output=True, text=True)
        print(r.stdout.strip().splitlines()[-1] if r.stdout.strip() else "", r.stderr.strip()[-2000:])
        if r.returncode != 0:
            sys.exit(f"probe_check: FAILED ({' '.join(cmd[:2])})")
print("probe_check: OK")
