#!/usr/bin/env python3
"""Per-ABI method matrix (T5.7): which fallback methods each APK can run, read from the native libraries it really
carries, checked against release-contract.json "methods_per_abi". A universal APK is checked once per ABI it contains.
usage: method_matrix.py <dir-with-apks>      (prints the matrix as JSON; exit 1 on any mismatch)"""
import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def contract():
    return json.loads((ROOT / "release-contract.json").read_text())


def methods_for(libs, c):
    """every method whose native libraries are all present (methods without natives run everywhere)"""
    needs = c["method_natives"]
    return sorted((m for m in c["methods"] if all(n in libs for n in needs.get(m, []))), key=c["methods"].index)


def apk_matrix(apk, c):
    with zipfile.ZipFile(apk) as z:
        libs = {}
        for n in z.namelist():
            m = re.fullmatch(r"lib/([^/]+)/([^/]+\.so)", n)
            if m:
                libs.setdefault(m.group(1), set()).add(m.group(2))
    return {abi: methods_for(libs[abi], c) for abi in ABIS if abi in libs}


def check(d):
    c = contract()
    want = {k: v for k, v in c["methods_per_abi"].items() if not k.startswith("_")}
    out, bad = {}, []
    for apk in sorted(pathlib.Path(d).glob("*.apk")):
        got = apk_matrix(apk, c)
        out[apk.name] = got
        name_abi = next((a for a in sorted(ABIS, key=len, reverse=True) if a in apk.name), "universal")
        if name_abi != "universal" and list(got) != [name_abi]:
            bad.append(f"{apk.name}: carries natives for {list(got)}, expected only {name_abi}")
        for abi, methods in got.items():
            if methods != want.get(abi):
                bad.append(f"{apk.name} [{abi}]: methods {methods} != release-contract {want.get(abi)}")
    if not out:
        bad.append("no APK checked")
    return out, bad


if __name__ == "__main__":
    out, bad = check(sys.argv[1])
    print(json.dumps(out, indent=1))
    if bad:
        print("\n".join(bad))
        sys.exit(1)
    print(f"OK: {len(out)} APK(s) match the per-ABI method matrix")
