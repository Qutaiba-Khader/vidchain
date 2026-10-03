# natives/ — native code VidChain adds (T5.1)

VidChain reuses the native runtime that youtubedl-android ships (Python 3.12, ffmpeg, QuickJS, aria2c).
Anything native that VidChain adds on top must fit that runtime and pass the gates below.

- `host-abi-manifest.json` — what the bundled Python really is per ABI: version, SOABI (`cpython-312`), the
  75 extension modules, what libpython and the extensions need, the bundled site-packages. Generated from the
  library AAR by `tools/natives_manifest.py`. CI checks it still matches the AAR (`--check`).
- `../natives.lock` — every native VidChain builds: source (URL + sha256), pinned build container (by digest),
  outputs (file names + sha256, content-addressed). Changed only by reviewed lock commits. Empty until the first
  native lands (lux, T5.6).
- `needed-allowlist.txt` — the only libraries our own natives may need: Android system libraries and what the
  library already ships in `usr/lib`.
- `elf-baseline.txt` — inherited findings in the library's natives, frozen; the gate fails only on new ones.
  Today: five libwebp libraries inside `libffmpeg.zip.so` are 4 KB aligned (see the file).

Gates: `tools/elf_gate.py` runs on every built APK in CI — machine per ABI folder, position independent,
`/system/bin/linker64` for executables, 16 KB `PT_LOAD` alignment on 64-bit ABIs (also inside the `*.zip.so`
archives the library unpacks), NEEDED allow-list for our natives. Its seeded 4 KB library is in
`tools/gate_selftest.sh`. Sources of everything built here go into the release's `SOURCES-<tag>.tar.zst`.
