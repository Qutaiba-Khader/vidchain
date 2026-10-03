import json
import os
import sys

from . import HTTP, LOGIN, NETWORK, NO_MEDIA, OK, UNAVAILABLE, UNSUPPORTED, USAGE, __version__


def emit(**kw):
    sys.stdout.write(json.dumps(kw, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def probe(_args):
    import platform
    import ssl
    mods = {}
    for name in ("gallery_dl", "requests", "urllib3", "idna", "certifi", "charset_normalizer", "you_get", "dukpy"):
        try:
            m = __import__(name)
            mods[name] = getattr(m, "__version__", "?")
        except Exception as e:  # noqa: BLE001
            mods[name] = "missing: " + type(e).__name__
    emit(event="probe", aio_engine=__version__, python=platform.python_version(), ssl=ssl.OPENSSL_VERSION, modules=mods)
    return OK if all(not str(v).startswith("missing") for v in mods.values()) else UNAVAILABLE


def gallery(args):
    """gallery-dl <url> --dest DIR [--ua UA] [--cookies FILE] [--referer URL]"""
    import argparse
    p = argparse.ArgumentParser(prog="aio_engine gallery-dl")
    p.add_argument("url")
    p.add_argument("--dest", required=True)
    p.add_argument("--ua")
    p.add_argument("--cookies")
    p.add_argument("--referer")
    a = p.parse_args(args)
    from gallery_dl import config, exception, job
    config.clear()
    config.set((), "base-directory", a.dest)
    config.set(("extractor",), "directory", [])          # all files flat in --dest
    config.set(("extractor",), "skip", False)
    config.set(("output",), "mode", "null")
    if a.ua:
        config.set(("extractor",), "user-agent", a.ua)
    if a.cookies:
        config.set(("extractor",), "cookies", a.cookies)
    if a.referer:
        config.set(("extractor",), "headers", {"Referer": a.referer})
    ca = os.environ.get("SSL_CERT_FILE")
    if ca:
        config.set(("downloader",), "verify", ca)
        config.set(("extractor",), "verify", ca)
    try:
        j = job.DownloadJob(a.url)
    except exception.NoExtractorError:
        emit(event="error", reason="no gallery-dl extractor for this URL")
        return UNSUPPORTED
    try:
        status = j.run()
    except exception.AuthenticationError as e:
        emit(event="error", reason=str(e)); return LOGIN
    except exception.AuthorizationError as e:
        emit(event="error", reason=str(e)); return LOGIN
    except exception.NotFoundError as e:
        emit(event="error", reason=str(e)); return UNAVAILABLE
    except exception.HttpError as e:
        emit(event="error", reason=str(e)); return HTTP
    except OSError as e:
        emit(event="error", reason=str(e)); return NETWORK
    files = sorted(os.path.join(r, f) for r, _, fs in os.walk(a.dest) for f in fs if not f.endswith(".part"))
    for f in files:
        emit(event="file", path=f, size=os.path.getsize(f))
    emit(event="done", status=status, files=len(files))
    if files:
        return OK
    return NO_MEDIA if not status else HTTP


def youget(args):
    """you-get --dest DIR [--ua UA] [--referer URL] -- <url>"""
    import argparse
    p = argparse.ArgumentParser(prog="aio_engine you-get")
    p.add_argument("url")
    p.add_argument("--dest", required=True)
    p.add_argument("--ua")
    p.add_argument("--referer")
    a = p.parse_args(args)
    import you_get.processor.ffmpeg as yff
    ff = os.environ.get("VIDCHAIN_FFMPEG")
    if ff and os.path.isfile(ff):                    # the library's ffmpeg is libffmpeg.so, not "ffmpeg" on PATH
        yff.FFMPEG, yff.FFPROBE, yff.FFMPEG_VERSION = ff, None, [7, 0]
    from you_get import common
    if a.ua:
        common.fake_headers["User-Agent"] = a.ua
    if a.referer:
        common.fake_headers["Referer"] = a.referer
    try:
        common.any_download(a.url, output_dir=a.dest, merge=True, info_only=False)
    except SystemExit as e:
        emit(event="error", reason="you-get stopped (%s)" % (e.code,))
    except NotImplementedError as e:
        emit(event="error", reason="unsupported: %s" % (e,)); return UNSUPPORTED
    except Exception as e:  # noqa: BLE001
        emit(event="error", reason="%s: %s" % (type(e).__name__, str(e)[:300]))
    files = sorted(os.path.join(r, f) for r, _, fs in os.walk(a.dest) for f in fs if not f.endswith((".download", ".part")))
    for f in files:
        emit(event="file", path=f, size=os.path.getsize(f))
    emit(event="done", files=len(files))
    return OK if files else NO_MEDIA


COMMANDS = {"probe": probe, "gallery-dl": gallery, "you-get": youget}


def main(argv):
    if not argv or argv[0] not in COMMANDS:
        emit(event="error", reason="usage: python -m aio_engine {%s} ..." % "|".join(COMMANDS))
        return USAGE
    return COMMANDS[argv[0]](argv[1:])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
