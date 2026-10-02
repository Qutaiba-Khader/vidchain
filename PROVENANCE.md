# Provenance

- `upstream/` is an untouched snapshot of shibaFoss/AIO-Video-Downloader at the commit recorded in
  `VENDORED_FROM` (tarball and tree sha256 recorded there; `tools/vendor.sh` re-verifies it).
  It is kept only as the licence and provenance record. This app does **not** sync with upstream.
- The app tree at the repository root started as an exact copy of `upstream/`. Every difference since
  then is declared in `BUILD-FIXES.md`, `REMOVED.md` or `seams.lock`, and `tools/drift_check.py`
  fails CI on anything undeclared.
- New code lives in new top-level areas (`fallback-core/`, `engines/`, `natives/`, `python/`, `tools/`,
  `ci/`) and never inside upstream-origin files except through the declared one-line seams.
- The repository history starts at an orphan root commit; no history of any other repository is included.
- Every release attaches the complete corresponding source (`SOURCES-<tag>.tar.zst`), as the licences require.
