#!/usr/bin/env python3
"""Cold start: wall time from the launcher tap to the main screen first drawn (T8.5, owner INBOX #8).

For each APK: install, one first start (unpacks the bundled runtimes, applies first-run defaults), then N cold
starts (force-stop, then launch). Each one is measured from system_server's own logcat timestamps: the
"START ... LauncherActivity" line to the "Displayed ...MotherActivity" line, so every splash, trampoline and
fixed delay in between is counted. Uninstalls afterwards (the APKs may be signed with different keys).
usage: coldstart.py <out.json> <label>=<apk> [<label>=<apk> ...]
"""
import json, re, statistics, subprocess, sys, time

PKG = "org.websnake.vidchain"
LAUNCHER = f"{PKG}/app.ui.others.startup.LauncherActivity"
RUNS = 5


def adb(*a, check=False):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check).stdout


def one_start(timeout=30):
    adb("shell", "am", "force-stop", PKG)
    time.sleep(2)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", LAUNCHER)
    t_end = time.time() + timeout
    while time.time() < t_end:
        log = adb("logcat", "-d", "-v", "epoch", "-s", "ActivityTaskManager:I")
        start = re.search(r"^\s*(\d+\.\d+).*START u0 .*LauncherActivity", log, re.M)
        shown = re.search(r"^\s*(\d+\.\d+).*Displayed " + re.escape(PKG) + r"/app\.ui\.main\.MotherActivity", log, re.M)
        if start and shown:
            return round((float(shown.group(1)) - float(start.group(1))) * 1000)
        time.sleep(0.5)
    return None


def main():
    out, results = sys.argv[1], {}
    for arg in sys.argv[2:]:
        label, apk = arg.split("=", 1)
        adb("uninstall", PKG)
        if "Success" not in adb("install", "-r", "-g", apk):
            results[label] = {"error": "install failed"}; continue
        adb("shell", "dumpsys", "deviceidle", "whitelist", "+" + PKG)
        adb("shell", "appops", "set", PKG, "MANAGE_EXTERNAL_STORAGE", "allow")
        first = one_start(timeout=90)
        time.sleep(10)  # let first-run work (runtime unpack) finish before measuring cold starts
        times = [one_start() for _ in range(RUNS)]
        ok = [t for t in times if t is not None]
        results[label] = {"apk": apk, "first_start_ms": first, "cold_ms": times,
                          "median_ms": statistics.median(ok) if ok else None}
        print(label, results[label], flush=True)
        adb("uninstall", PKG)
    json.dump(results, open(out, "w"), indent=1)


if __name__ == "__main__":
    main()
