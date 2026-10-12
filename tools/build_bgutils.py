#!/usr/bin/env python3
"""T8.12: build engines/engine-web/src/main/assets/vidchain-bgutils.js from the pinned npm package bgutils-js (LuanRT/BgUtils,
MIT) - no npm or bundler needed: the package has no dependencies, so its ES modules are joined in dependency order with
their import/export keywords removed, inside one function that sets window.VidChainBg. The tarball is checked against
the registry's sha512 before anything is read.
usage: tools/build_bgutils.py [--check]   (--check: fail if the committed asset differs from a fresh build)"""
import base64
import hashlib
import io
import pathlib
import re
import sys
import tarfile
import urllib.request

VERSION = "4.0.3"
URL = f"https://registry.npmjs.org/bgutils-js/-/bgutils-js-{VERSION}.tgz"
SHA512 = "nvhrzqRYqFcC3AJbf1fENc/jzyMk95wH9UC9ruQzTZCq+itYWuHi2a9leK/bC1lLfi+R4a28wJZxpD5KiTFf+w=="
ORDER = ["dist/utils/constants.js", "dist/utils/helpers.js", "dist/utils/EventEmitterLike.js",
         "dist/core/BotGuardClient.js", "dist/core/ChallengeFetcher.js", "dist/core/WebPoMinter.js"]
EXPOSE = ["getChallenge", "BotGuardClient", "WebPoMinter", "buildURL", "getHeaders", "base64ToU8", "u8ToBase64"]
OUT = pathlib.Path(__file__).resolve().parents[1] / "engines/engine-web/src/main/assets/vidchain-bgutils.js"


def build() -> str:
    raw = urllib.request.urlopen(URL, timeout=60).read()
    got = base64.b64encode(hashlib.sha512(raw).digest()).decode()
    if got != SHA512:
        sys.exit(f"build_bgutils: sha512 mismatch for {URL}: {got}")
    tar = tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz")
    licence = tar.extractfile("package/LICENSE").read().decode()
    parts = []
    for name in ORDER:
        src = tar.extractfile("package/" + name).read().decode()
        src = re.sub(r"^import\s.*?;\s*$", "", src, flags=re.M)
        src = re.sub(r"^//# sourceMappingURL=.*$", "", src, flags=re.M)
        src = re.sub(r"^export\s+(?=(?:async\s+)?(?:function|class|const|let)\b)", "", src, flags=re.M)
        if re.search(r"^\s*(?:import|export)\b", src, flags=re.M):
            sys.exit(f"build_bgutils: unhandled import/export left in {name}")
        parts.append(f"// ---- {name} ----\n{src.strip()}\n")
    header = (f"/* vidchain-bgutils.js - built by tools/build_bgutils.py from bgutils-js {VERSION} ({URL}, sha512 {SHA512}).\n"
              " * BgUtils by LuanRT, MIT licence:\n * " + licence.strip().replace("\n", "\n * ") + "\n */\n")
    body = "\n".join(parts)
    return header + "(function(){\n'use strict';\n" + body + f"\nwindow.VidChainBg = {{ {', '.join(EXPOSE)} }};\n}})();\n"


if __name__ == "__main__":
    js = build()
    if "--check" in sys.argv:
        if not OUT.is_file() or OUT.read_text() != js:
            sys.exit("build_bgutils: the committed asset differs from a fresh build")
        print("build_bgutils: OK")
    else:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(js)
        print(f"wrote {OUT} ({len(js)} chars)")
