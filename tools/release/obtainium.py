#!/usr/bin/env python3
"""The Obtainium entry for VidChain - single source of truth for the README add link and check_links.py.
Obtainium's GitHub source reads the newest release, takes the version from the tag (v<versionName>)
and, with autoApkFilterByArch, downloads the APK matching the phone's CPU. The repo is public, so no
token is needed."""
import json
import pathlib
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / "release-contract.json").read_text())
REPO = "Qutaiba-Khader/vidchain"
CONFIG = {
    "id": CONTRACT["package"],
    "url": f"https://github.com/{REPO}",
    "author": "Qutaiba-Khader",
    "name": "VidChain",
    "additionalSettings": json.dumps(CONTRACT["obtainium"], separators=(",", ":")),
}


def deep_link():
    return "obtainium://app/" + urllib.parse.quote(json.dumps(CONFIG, separators=(",", ":")), safe="")


def add_url():
    # GitHub strips obtainium:// links from Markdown; Obtainium's https redirect page opens the deep link.
    return "https://apps.obtainium.imranr.dev/redirect?r=" + urllib.parse.quote(deep_link(), safe="")


if __name__ == "__main__":
    print(json.dumps({"config": CONFIG, "deep_link": deep_link(), "add_url": add_url()}, indent=2))
