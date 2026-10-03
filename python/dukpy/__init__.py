"""dukpy compatible shim (T5.4) for you-get: instead of the native Duktape extension (not available for the
bundled Python), each call runs a small script in the QuickJS the library already ships (libqjs.so), with the
keyword arguments as the global `dukpy` object, and returns the JSON value of the last expression - like dukpy."""
import json
import os
import shutil
import subprocess
import tempfile

__version__ = "0.4.1-vidchain-qjs"


class JSRuntimeError(Exception):
    pass


def _qjs():
    q = os.environ.get("VIDCHAIN_QJS") or shutil.which("qjs")
    if not q:
        raise JSRuntimeError("no QuickJS (VIDCHAIN_QJS not set, no qjs on PATH)")
    return q


def evaljs(code, **kwargs):
    if isinstance(code, (list, tuple)):
        code = ";\n".join(code)
    src = (
        "var dukpy = %s;\n"
        "var __vidchain_result = (0, eval)(%s);\n"
        "print(JSON.stringify(__vidchain_result === undefined ? null : __vidchain_result));\n"
    ) % (json.dumps(kwargs), json.dumps(code))
    fd, path = tempfile.mkstemp(suffix=".js", prefix="vidchain-dukpy-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(src)
        p = subprocess.run([_qjs(), path], capture_output=True, timeout=120)
        out = p.stdout.decode("utf-8", "replace").strip()
        if p.returncode != 0 or not out:
            raise JSRuntimeError((p.stderr.decode("utf-8", "replace") or out or "QuickJS failed").strip()[:500])
        return json.loads(out.splitlines()[-1])
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


class JSInterpreter:
    """dukpy.JSInterpreter compatibility: every evaljs call is independent here (no state kept between calls)."""

    def evaljs(self, code, **kwargs):
        return evaljs(code, **kwargs)
