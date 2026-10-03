#!/usr/bin/env python3
"""Builds the Python engines zip (T5.3): pinned pure wheels (python/requirements.lock, sha256-checked) + the
aio_engine launcher, as one deterministic zip importable through PYTHONPATH (zipimport). Refuses anything native
(.so/.pyd) or mypyc-compiled, which the bundled Python could not load.
usage: build_pyzip.py --out <zip>   (wheels cached in ~/.cache/vidchain-wheels)"""
import argparse
import hashlib
import io
import json
import pathlib
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = pathlib.Path.home() / ".cache" / "vidchain-wheels"
FIXED = (2020, 1, 1, 0, 0, 0)


def wheel(entry):
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / entry["file"]
    if not f.exists() or hashlib.sha256(f.read_bytes()).hexdigest() != entry["sha256"]:
        data = urllib.request.urlopen(entry["url"], timeout=60).read()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            sys.exit(f"sha256 mismatch for {entry['file']}")
        f.write_bytes(data)
    return f.read_bytes()


def native(name):
    n = name.lower()
    return n.endswith((".so", ".pyd", ".dylib", ".dll")) or "__mypyc" in n or ".cpython-" in n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    lock = [json.loads(l) for l in (ROOT / "python" / "requirements.lock").read_text().splitlines() if l.strip() and not l.startswith("#")]
    files = {}
    for e in lock:
        if not e["file"].endswith("-none-any.whl"):
            sys.exit(f"{e['file']}: only pure (none-any) wheels are allowed")
        with zipfile.ZipFile(io.BytesIO(wheel(e))) as z:
            for n in z.namelist():
                if n.endswith("/") or ".data/" in n.split("/")[0]:
                    continue
                if native(n):
                    sys.exit(f"{e['file']}: native or mypyc file {n} - the bundled Python cannot load it")
                files[n] = z.read(n)
    for p in sorted((ROOT / "python" / "aio_engine").rglob("*.py")):
        files["aio_engine/" + p.relative_to(ROOT / "python" / "aio_engine").as_posix()] = p.read_bytes()
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for n in sorted(files):
            zi = zipfile.ZipInfo(n, FIXED)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, files[n])
    out.write_bytes(buf.getvalue())
    print(f"{out}: {len(files)} files, {out.stat().st_size // 1024} KB, sha256 {hashlib.sha256(buf.getvalue()).hexdigest()[:16]}")


if __name__ == "__main__":
    main()
