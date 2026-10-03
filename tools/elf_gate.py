#!/usr/bin/env python3
"""ELF gate (T5.1): every native library in the APKs (or a folder of lib/<abi>/*.so) is checked:
- its machine matches the ABI folder it sits in;
- it is position independent (ET_DYN); an executable's interpreter is /system/bin/linker64 (or linker on 32-bit);
- 64-bit ABIs: every PT_LOAD is aligned to at least 16 KB (Android 15+ 16 KB page devices);
- our own natives (listed in natives.lock "outputs") only need libraries from natives/needed-allowlist.txt.
usage: elf_gate.py <apk-or-dir> [...]      exit 1 on any violation"""
import json
import pathlib
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from elf import ABI_MACHINE, ET_DYN, Elf  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = 0x4000


def libs(target: pathlib.Path):
    if target.is_file() and target.suffix == ".apk":
        with zipfile.ZipFile(target) as z:
            for n in z.namelist():
                parts = n.split("/")
                if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                    yield f"{target.name}:{n}", parts[1], z.read(n)
    elif target.is_dir():
        for p in sorted(target.rglob("*.so")):
            abi = p.parent.name
            if abi in ABI_MACHINE:
                yield str(p), abi, p.read_bytes()
        for apk in sorted(target.rglob("*.apk")):
            yield from libs(apk)


def ours():
    lock = ROOT / "natives.lock"
    if not lock.exists():
        return set()
    return {o for n in json.loads(lock.read_text()).get("natives", []) for o in n.get("outputs", [])}


def baseline():
    f = ROOT / "natives" / "elf-baseline.txt"
    return {l.strip() for l in f.read_text().splitlines() if l.strip() and not l.startswith("#")} if f.exists() else set()


def key(err):
    """the baseline key of a violation: '<abi> <file>[!member] <check>'"""
    path, msg = err.split(": ", 1)
    outer, _, member = path.partition("!")
    parts = outer.split("/")
    abi = next((p for p in reversed(parts) if p in ABI_MACHINE), "?")
    name = parts[-1] + (f"!{member}" if member else "")
    check = "align" if "PT_LOAD" in msg else msg.split(" ")[0]
    return f"{abi} {name} {check}"


def allowlist():
    f = ROOT / "natives" / "needed-allowlist.txt"
    return {l.strip() for l in f.read_text().splitlines() if l.strip() and not l.startswith("#")} if f.exists() else set()


def check(name, abi, data, own, allowed):
    if data[:2] == b"PK":
        # libpython.zip.so / libffmpeg.zip.so: archives the library unpacks at run time; their libraries are dlopen'ed too
        errs = []
        with zipfile.ZipFile(__import__("io").BytesIO(data)) as z:
            for m in z.namelist():
                b = z.read(m) if (".so" in m.rsplit("/", 1)[-1]) else b""
                if b[:4] == b"\x7fELF":
                    errs += check(f"{name}!{m}", abi, b, own, allowed)
        return errs
    errs = []
    try:
        e = Elf(data)
    except Exception as x:  # noqa: BLE001
        return [f"{name}: unreadable ELF ({x})"]
    if e.machine != ABI_MACHINE[abi]:
        errs.append(f"{name}: machine {e.machine} in {abi}")
    if e.e_type != ET_DYN:
        errs.append(f"{name}: not position independent (e_type {e.e_type})")
    interp = e.interp
    if interp is not None and interp not in ("/system/bin/linker64", "/system/bin/linker"):
        errs.append(f"{name}: interpreter {interp}")
    if abi in ("arm64-v8a", "x86_64") and e.min_load_align < PAGE:
        errs.append(f"{name}: PT_LOAD aligned to {e.min_load_align:#x}, needs >= {PAGE:#x} (16 KB pages)")
    base = name.rsplit("/", 1)[-1]
    if base in own:
        needed, _ = e.dynamic()
        extra = [n for n in needed if n not in allowed]
        if extra:
            errs.append(f"{name}: needs libraries outside the allow-list: {extra}")
    return errs


def main(argv):
    if not argv:
        sys.exit(__doc__)
    own, allowed = ours(), allowlist()
    errors, count = [], 0
    for t in argv:
        for name, abi, data in libs(pathlib.Path(t)):
            count += 1
            errors += check(name, abi, data, own, allowed)
    known = baseline()
    inherited = [e for e in errors if key(e) in known]
    new = [e for e in errors if key(e) not in known]
    for e in new:
        print("ELF FAIL:", e)
    print(f"elf_gate: {count} native libraries, {len(new)} new violation(s), {len(inherited)} inherited (natives/elf-baseline.txt)")
    sys.exit(1 if new or count == 0 else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
