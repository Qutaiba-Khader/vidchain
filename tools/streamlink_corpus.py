#!/usr/bin/env python3
"""Streamlink corpus (T5.5): runs Streamlink's OWN tests (parse, validate, DASH, l10n, every plugin's tests) against
the engines zip - i.e. with our lxml and pycountry stand-ins instead of the real native packages. Core suites must
pass; the shipped plugin set is the plugins whose own tests pass. Writes python/streamlink-plugins.json.
usage: streamlink_corpus.py [--write]   (needs network for the sdist and the test tools)"""
import hashlib
import json
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SDIST = {"url": "https://files.pythonhosted.org/packages/source/s/streamlink/streamlink-8.6.1.tar.gz", "sha256": None}
# test tools, plus pycryptodomex standing in for the Cryptodome that the library's Python bundles natively
TEST_TOOLS = ["pytest==8.4.2", "requests-mock==1.12.1", "freezegun==1.5.5", "pytest-trio==0.8.0", "pycryptodomex==3.23.0"]
CORE = ["tests/utils/test_parse.py", "tests/test_validate.py", "tests/stream/dash", "tests/utils/test_l10n.py"]


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def main():
    lock = [json.loads(l) for l in (ROOT / "python" / "requirements.lock").read_text().splitlines() if l.strip() and not l.startswith("#")]
    sdist = next(e for e in lock if e["name"] == "streamlink-sdist")
    work = pathlib.Path(tempfile.mkdtemp(prefix="vidchain-corpus-"))
    data = urllib.request.urlopen(sdist["url"], timeout=120).read()
    if hashlib.sha256(data).hexdigest() != sdist["sha256"]:
        sys.exit("streamlink sdist sha256 mismatch")
    (work / "sdist.tgz").write_bytes(data)
    with tarfile.open(work / "sdist.tgz") as t:
        t.extractall(work, filter="data")
    src = next(work.glob("streamlink-*/"))
    sh([sys.executable, str(ROOT / "tools" / "build_pyzip.py"), "--all-plugins", "--out", str(work / "py.zip")])
    site = work / "site"
    zipfile.ZipFile(work / "py.zip").extractall(site)
    # import smoke on the zip ALONE (python -S: no site-packages, so test tools cannot supply a missing module):
    # Streamlink's core and every plugin module must import from what ships
    smoke = (
        "import importlib, pkgutil, sys, streamlink.plugins as P\n"
        "import streamlink.session, streamlink.stream.hls, streamlink.stream.dash, streamlink.stream.ffmpegmux, streamlink.utils.l10n\n"
        "bad = []\n"
        "for m in pkgutil.iter_modules(P.__path__):\n"
        "    try:\n"
        "        importlib.import_module('streamlink.plugins.' + m.name)\n"
        "    except ModuleNotFoundError as e:\n"
        "        bad.append(m.name + ': ' + str(e))\n"
        "print('\\n'.join(bad)); sys.exit(1 if bad else 0)\n"
    )
    # import-only placeholder for Cryptodome, which the phone's Python bundles natively (host-ABI manifest site_packages)
    cd = work / "cryptodome-placeholder" / "Cryptodome"
    anything = "class _Any:\n    block_size = 16\n    def __getattr__(self, n): return _Any()\n    def __call__(self, *a, **k): return _Any()\n"
    for sub, body in {"": "", "Cipher": anything + "AES = PKCS1_v1_5 = _Any()\n", "Hash": anything + "MD5 = SHA256 = _Any()\n",
                      "PublicKey": anything + "RSA = _Any()\n", "Util": ""}.items():
        (cd / sub).mkdir(parents=True, exist_ok=True)
        (cd / sub / "__init__.py").write_text(body)
    (cd / "Util" / "Padding.py").write_text("def pad(*a, **k): pass\ndef unpad(*a, **k): pass\n")
    r = subprocess.run([sys.executable, "-S", "-c", smoke], env={"PYTHONPATH": f"{site}:{cd.parent}", "HOME": str(work)}, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("modules missing from the engines zip:\n" + r.stdout + r.stderr[-2000:])
    sh([sys.executable, "-m", "venv", str(work / "venv")])
    py = str(work / "venv" / "bin" / "python")
    sh([py, "-m", "pip", "install", "-q"] + TEST_TOOLS)
    env = {"PYTHONPATH": str(site), "PATH": "/usr/bin:/bin", "HOME": str(work), "LC_ALL": "C.UTF-8"}
    junit = work / "junit.xml"
    plugin_tests = sorted(str(p.relative_to(src)) for p in (src / "tests" / "plugins").glob("test_*.py"))
    subprocess.run([py, "-m", "pytest", "-q", "-p", "no:cacheprovider", "--continue-on-collection-errors", "--junitxml", str(junit), *CORE, *plugin_tests],
                   cwd=src, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    files = {}
    for tc in ET.parse(junit).getroot().iter("testcase"):
        f = tc.get("file")
        if not f:
            # classname "tests.plugins.test_x.TestClass" (or a module path for collection errors) -> tests/plugins/test_x.py
            mods = []
            for part in tc.get("classname", "").split("."):
                if part[:1].isupper():
                    break
                mods.append(part)
            f = "/".join(mods) + ".py"
        bad = tc.find("failure") is not None or tc.find("error") is not None
        files.setdefault(f, [0, 0])[1 if bad else 0] += 1
    # collection errors appear as testcases without file: map them by classname
    core_bad = {f: v for f, v in files.items() if not f.startswith("tests/plugins") and v[1]}
    shipped, excluded = [], {}
    for t in plugin_tests:
        name = t.rsplit("test_", 1)[1][:-3]
        ok, bad = files.get(t, files.get(t.replace("/", ".")[:-3].replace(".", "/") + ".py", [0, 1]))
        (shipped.append(name) if bad == 0 and ok > 0 else excluded.__setitem__(name, f"{bad} failing / {ok} passing"))
    core_ok = sum(v[0] for f, v in files.items() if not f.startswith("tests/plugins"))
    core_failed = sum(v[1] for f, v in files.items() if not f.startswith("tests/plugins"))
    result = {"streamlink": "8.6.1", "core_passed": core_ok, "core_failed": core_failed, "core_failing_files": sorted(core_bad),
              "shipped_plugins": shipped, "excluded_plugins": excluded}
    print(json.dumps({k: (v if not isinstance(v, (list, dict)) else len(v)) for k, v in result.items()}))
    if "--check" in sys.argv:
        committed = json.loads((ROOT / "python" / "streamlink-plugins.json").read_text())
        lost = sorted(set(committed["shipped_plugins"]) - set(shipped))
        if lost:
            sys.exit(f"shipped plugins now failing their own tests: {lost}")
    if "--write" in sys.argv:
        (ROOT / "python" / "streamlink-plugins.json").write_text(json.dumps(result, indent=1, sort_keys=True) + "\n")
    if core_failed:
        for f, v in sorted(files.items()):
            if v[1] and not f.startswith("tests/plugins"):
                print("CORE FAIL", f, v)
        sys.exit(1)


if __name__ == "__main__":
    main()
