#!/usr/bin/env python3
"""Host-ABI manifest (T5.1): what the bundled Python of youtubedl-android really is, per ABI, read from the AAR:
version, SOABI, lib-dynload modules, NEEDED of libpython and of every extension module. Natives we add (gallery-dl
wheels, lxml, ...) must match this. usage: natives_manifest.py [--check]   (--check: committed manifest == AAR)"""
import glob
import io
import json
import os
import pathlib
import re
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from elf import Elf  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "natives" / "host-abi-manifest.json"


def find_aar(name):
    hits = sorted(glob.glob(os.path.expanduser(f"~/.gradle/caches/modules-2/files-2.1/io.github.junkfood02.youtubedl-android/{name}/*/*/{name}-*.aar")))
    if not hits:
        sys.exit(f"{name} AAR not in the Gradle cache - build the app once first")
    return hits[-1]


def manifest():
    aar = find_aar("library")
    version = re.search(r"/library/([^/]+)/", aar).group(1)
    out = {"source": f"io.github.junkfood02.youtubedl-android:library:{version}", "abis": {}}
    with zipfile.ZipFile(aar) as z:
        for abi in ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]:
            name = f"jni/{abi}/libpython.zip.so"
            if name not in z.namelist():
                continue
            inner = zipfile.ZipFile(io.BytesIO(z.read(name)))
            names = inner.namelist()
            lib = next(n for n in names if re.search(r"usr/lib/libpython3\.\d+\.so(\.\d+)*$", n))
            pyver = re.search(r"libpython(3\.\d+)", lib).group(1)
            dyn = sorted(n for n in names if f"python{pyver}/lib-dynload/" in n and n.endswith(".so"))
            soabi = sorted({m.group(1) for n in dyn for m in [re.search(r"\.(cpython-[^.]+)\.so$", n)] if m})
            ext_needed = set()
            for n in dyn:
                needed, _ = Elf(inner.read(n)).dynamic()
                ext_needed.update(needed)
            libpy_needed, soname = Elf(inner.read(lib)).dynamic()
            site = sorted({n.split("site-packages/")[1].split("/")[0] for n in names if "site-packages/" in n and n.split("site-packages/")[1]})
            usr_libs = sorted(os.path.basename(n) for n in names if re.match(r"usr/lib/[^/]+\.so", n))
            out["abis"][abi] = {
                "python": pyver, "soabi": soabi, "libpython": {"file": os.path.basename(lib), "soname": soname, "needed": sorted(libpy_needed)},
                "lib_dynload": [os.path.basename(n) for n in dyn], "extension_needed": sorted(ext_needed),
                "usr_lib": usr_libs, "site_packages": site,
            }
    return out


def main():
    m = manifest()
    text = json.dumps(m, indent=1, sort_keys=True) + "\n"
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            sys.exit("host-abi-manifest.json does not match the library AAR - regenerate with tools/natives_manifest.py")
        print(f"OK: host-abi manifest matches {m['source']}")
        return
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}: " + ", ".join(f"{a} python {v['python']} ({len(v['lib_dynload'])} ext)" for a, v in m["abis"].items()))


if __name__ == "__main__":
    main()
