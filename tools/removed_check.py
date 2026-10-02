#!/usr/bin/env python3
"""Removed-features gate (source level), run in CI on every PR and push.

Proves that ads, tracking, the self-updater, the remote kill switch and developer cloud sync stay
removed, and that the UI does not pretend otherwise. It never lists or blocks sites (owner Q27/Q28).

usage: tools/removed_check.py [tree]      default tree = repository root; exit 1 on any failure.
Negative control: `tools/removed_check.py upstream` must FAIL (the original code has all of it).
"""
import pathlib
import re
import sys

SRC = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent).resolve()
MAIN = SRC / "app" / "src" / "main"
JAVA = MAIN / "java"
BACKEND = JAVA / "app" / "core" / "engines" / "backend"
BLACKHOLE = "http://127.0.0.1:9"
failures = []


def check(ok, msg):
    print(("  ok   " if ok else "  FAIL ") + msg)
    if not ok:
        failures.append(msg)


TOKENS = re.compile(r'("(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\')|/\*.*?\*/|//[^\n]*', re.S)


def code_of(f):
    # strip comments, keep string literals ("http://..." contains "//")
    return TOKENS.sub(lambda m: m.group(1) or "", f.read_text(encoding="utf-8", errors="replace"))


code = {f: code_of(f) for f in JAVA.rglob("*") if f.suffix in (".kt", ".java")}
res = {f: f.read_text(encoding="utf-8", errors="replace")
       for f in MAIN.rglob("*") if f.is_file() and f.suffix in (".xml", ".json", ".properties")}
rel = lambda f: str(f.relative_to(SRC))
check(len(code) > 150, f"scanned {len(code)} source files (a broken scan would see far fewer)")


def one(name):
    hits = [t for f, t in code.items() if f.name == name]
    check(len(hits) == 1, f"{name} found ({len(hits)})")
    return hits[0] if len(hits) == 1 else ""


print("1. ad gate stays off ('Watch Ad To Download' needs both flags false)")
app = one("AIOApp.kt")
check(re.search(r"\bvar\s+IS_PREMIUM_USER\s*=\s*true\b", app) is not None, "IS_PREMIUM_USER = true")
check(re.search(r"\bvar\s+IS_ULTIMATE_VERSION_UNLOCKED\s*=\s*true\b", app) is not None, "IS_ULTIMATE_VERSION_UNLOCKED = true")
flips = sorted(rel(f) for f, t in code.items()
               if re.search(r"\b(IS_PREMIUM_USER|IS_ULTIMATE_VERSION_UNLOCKED)\s*=(?!=)", t) and f.name != "AIOApp.kt")
check(not flips, f"nothing else assigns the premium flags {flips or ''}")

print("2. developer servers (Parse/Back4App, Supabase) and geolocation")
units = MAIN / "res" / "values" / "strings_unit_ids.xml"
t = units.read_text() if units.exists() else ""
check(bool(t), "strings_unit_ids.xml present")
for key in ("text_back4app_server_url", "text_supabase_client_key"):
    m = re.search(rf'name="{key}"[^>]*>([^<]*)<', t)
    check(bool(m) and m.group(1).startswith(BLACKHOLE), f"{key} -> {m.group(1) if m else 'missing'}")
DEV = re.compile(r"back4app\.(io|com)|parseapi\.|\.supabase\.(co|in)|tubeaio\.|ip-api\.com|ipapi\.co|ipinfo\.io|ipwho\.is", re.I)
hits = sorted({rel(f) for f, x in list(code.items()) + list(res.items()) if DEV.search(x)})
check(not hits, f"no developer / geolocation host anywhere {hits or ''}")

print("3. self-updater, kill switch, crash/download/usage reporting")
upd = one("AIOUpdater.kt")
urls = re.findall(r'"(https?://[^"]+)"', upd)
check(bool(urls) and all(u.startswith(BLACKHOLE) for u in urls), f"self-updater only reaches {BLACKHOLE} {urls}")
for f, needle in (("AIOSelfDestruct.kt", "fun shouldSelfDestructApplication() = Unit"),
                  ("AIOBackend.kt", "fun saveDownloadLog("), ("AppUsageTimer.kt", "fun startTracking() = Unit")):
    p = BACKEND / f
    body = code_of(p) if p.exists() else ""
    check(needle in body and "http" not in body and "Parse" not in body.replace("initParseBackend", ""),
          f"{f} is the no-op stub")

print("4. no ad / tracking library")
AD = re.compile(r"play-services-ads|gms[.:]ads|\badmob|applovin|ironsource|unity3d|unityads|levelplay|"
                r"vungle|startapp|chartboost|mbridge|mintegral|pangle|bytedance|inmobi|appodeal|adcolony|"
                r"firebase|crashlytics|sentry|onesignal|appsflyer|mixpanel|amplitude", re.I)
for cfg in (SRC / "gradle" / "libs.versions.toml", SRC / "app" / "build.gradle", SRC / "build.gradle"):
    if cfg.exists():
        found = sorted({m.group(0) for m in AD.finditer(cfg.read_text())})
        check(not found, f"{cfg.relative_to(SRC)} {found or 'clean'}")
libs = sorted(p.name for p in (SRC / "app" / "libs").glob("*")) if (SRC / "app" / "libs").exists() else []
check(not libs, f"no bundled jar/aar in app/libs {libs or ''}")
imports = sorted({rel(f) for f, x in code.items()
                  if re.search(r"^\s*import\s+com\.(google\.android\.gms\.ads|unity3d|applovin|ironsource|"
                               r"facebook\.ads|google\.firebase|bytedance)", x, re.M)})
check(not imports, f"no ad/tracking SDK imports {imports or ''}")

print("5. the UI does not offer what cannot work")
lay = MAIN / "res" / "layout" / "frag_settings_1_main_1.xml"
L = lay.read_text() if lay.exists() else ""


def tag_with(attr_value, before=False):
    """the opening tag that contains attr_value (or, with before=True, the closest enclosing <LinearLayout ...> before it)"""
    i = L.find(attr_value)
    if i < 0:
        return ""
    if before:
        j = L.rfind("<LinearLayout", 0, i)
        return L[j:L.find(">", j)]
    return L[L.rfind("<", 0, i):L.find(">", i)]


check('android:visibility="gone"' in tag_with('@+id/btn_check_new_update'), "settings: update-check row hidden")
check('android:visibility="gone"' in tag_with('@+id/txt_suggest_for_sign_up', before=True), "settings: developer-cloud sign-in block hidden")
fb = one("UserFeedbackActivity.java")
check("saveUserFeedback" not in fb and "title_feedbacks_sent_successfully" not in fb,
      "feedback screen does not claim to send to a server")
check("/issues/new?body=" in fb, "feedback screen opens a pre-filled GitHub issue instead")

print(f"\n{'FAILED' if failures else 'PASSED'}: {len(failures)} failure(s)")
sys.exit(1 if failures else 0)
