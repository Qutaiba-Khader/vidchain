#!/usr/bin/env python3
"""Open-source licences of everything the app ships (T6.3).
Libraries: the app's resolved release runtime classpath (`gradle :app:dependencies`), each licence read from its POM in
the Gradle cache (following <parent> POMs). Native and Python components: natives/licences-manual.json (hand-kept,
checked against natives.lock, python/requirements.lock and the youtubedl-android runtime).
usage: licences.py <deps.txt> <out.json>            write the asset the licences screen reads
       licences.py <deps.txt> <out.json> --check    fail when the committed asset is out of date"""
import json
import os
import pathlib
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = pathlib.Path(os.environ.get("GRADLE_USER_HOME", pathlib.Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
NS = "{http://maven.apache.org/POM/4.0.0}"
COORD = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([0-9][^ ()]*?)(?: -> ([^ ()]+))?(?: \(\*\)| \(c\))?$")


def coords(deps_txt):
    out = set()
    for line in open(deps_txt):
        m = COORD.search(line.rstrip())
        if m and "(c)" not in line:
            g, a, v, v2 = m.groups()
            out.add((g, a, v2 or v))
    return sorted(out)


REPOS = ["https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"]
POM_CACHE = pathlib.Path(os.environ.get("VIDCHAIN_POM_CACHE", "/tmp/vidchain-poms"))


def pom(g, a, v):
    """the POM from the Gradle cache, else from Google Maven / Maven Central (Gradle keeps only module metadata for many)"""
    d = CACHE / g / a / v
    paths = list(d.glob(f"*/{a}-{v}.pom")) if d.is_dir() else []
    if not paths:
        local = POM_CACHE / f"{g}__{a}__{v}.pom"
        if not local.exists():
            for repo in REPOS:
                try:
                    with urllib.request.urlopen(f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom", timeout=30) as r:
                        POM_CACHE.mkdir(parents=True, exist_ok=True)
                        local.write_bytes(r.read())
                        break
                except Exception:  # noqa: BLE001 - try the next repository
                    continue
        paths = [local] if local.exists() else []
    for p in paths:
        try:
            return ET.parse(p).getroot()
        except ET.ParseError:
            return None
    return None


def text(el, tag):
    x = el.find(NS + tag) if el is not None else None
    if x is None and el is not None:
        x = el.find(tag)
    return (x.text or "").strip() if x is not None and x.text else ""


def licences(g, a, v, depth=0):
    root = pom(g, a, v)
    if root is None:
        return [], ""
    lic = root.find(NS + "licenses") if root.find(NS + "licenses") is not None else root.find("licenses")
    names = []
    if lic is not None:
        for l in list(lic):
            n, u = text(l, "name"), text(l, "url")
            if n or u:
                names.append({"name": n or u, "url": u})
    url = text(root, "url")
    if not names and depth < 5:
        par = root.find(NS + "parent") if root.find(NS + "parent") is not None else root.find("parent")
        if par is not None:
            pn, pu = licences(text(par, "groupId"), text(par, "artifactId"), text(par, "version"), depth + 1)
            return pn, url or pu
    return names, url


WHEEL_CACHE = pathlib.Path(os.environ.get("VIDCHAIN_WHEEL_CACHE", "/tmp/vidchain-wheels"))


def python_components():
    """every wheel in python/requirements.lock, with the licence its own METADATA declares (sha256-checked download)"""
    import hashlib, zipfile, email.parser
    out = []
    for line in (ROOT / "python/requirements.lock").read_text().splitlines():
        if not line.startswith("{"):
            continue
        w = json.loads(line)
        if w.get("tests_only"):
            continue
        f = WHEEL_CACHE / w["file"]
        if not f.exists():
            WHEEL_CACHE.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(w["url"], timeout=60) as r:
                f.write_bytes(r.read())
        if hashlib.sha256(f.read_bytes()).hexdigest() != w["sha256"]:
            raise SystemExit(f"{w['file']}: sha256 differs from requirements.lock")
        with zipfile.ZipFile(f) as z:
            meta = next(n for n in z.namelist() if n.endswith(".dist-info/METADATA"))
            m = email.parser.Parser().parsestr(z.read(meta).decode("utf-8", "replace"), headersonly=True)
        lic = m.get("License-Expression") or ""
        if not lic:
            cls = [c.split("::")[-1].strip() for c in m.get_all("Classifier", []) if c.startswith("License ::")]
            lic = " OR ".join(cls) or (m.get("License") or "").splitlines()[0][:80]
        out.append({"name": w["name"], "version": w["version"], "licence": lic or "see its source", "kind": "Python package (pure wheel)",
                    "url": m.get("Home-page") or next((u.split(",", 1)[1].strip() for u in m.get_all("Project-URL", []) if "," in u), "")})
    return out


def main():
    deps, out = sys.argv[1], pathlib.Path(sys.argv[2])
    libs, missing = [], []
    for g, a, v in coords(deps):
        names, url = licences(g, a, v)
        if not names:
            missing.append(f"{g}:{a}:{v}")
        libs.append({"id": f"{g}:{a}", "version": v, "licences": names, "url": url})
    manual = json.loads((ROOT / "natives/licences-manual.json").read_text())
    for m in manual.get("libraries_without_pom_licence", []):
        for lib in libs:
            if lib["id"] == m["id"] and (not lib["licences"] or all(l["name"] == "Other" for l in lib["licences"])):
                lib["licences"] = m["licences"]; lib["note"] = m.get("note", "")
                missing = [x for x in missing if not x.startswith(m["id"] + ":")]
    doc = {"_note": "generated by tools/licences.py from the release runtime classpath; do not edit (edit natives/licences-manual.json)",
           "original_app": manual["original_app"], "libraries": libs,
           "native_and_python": manual["components"] + python_components()}
    text_out = json.dumps(doc, indent=1, ensure_ascii=False, sort_keys=False) + "\n"
    if missing:
        print("no licence found for:\n  " + "\n  ".join(missing))
        sys.exit(1)
    if "--check" in sys.argv:
        if not out.exists() or out.read_text() != text_out:
            import difflib
            old = out.read_text().splitlines() if out.exists() else []
            print(f"{out} is out of date: run tools/licences.py {deps} {out}")
            print("\n".join(list(difflib.unified_diff(old, text_out.splitlines(), "committed", "generated", n=1, lineterm=""))[:80]))
            sys.exit(1)
        print(f"OK: {len(libs)} libraries + {len(doc['native_and_python'])} native / Python components, asset up to date")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text_out)
    print(f"wrote {out}: {len(libs)} libraries + {len(doc['native_and_python'])} native / Python components")


if __name__ == "__main__":
    main()
