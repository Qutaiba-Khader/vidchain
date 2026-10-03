"""VidChain's Python launcher (T5.3): one entry point for the Python engines that run on the bundled Python of
youtubedl-android - gallery-dl today, you-get and Streamlink later. Output: JSON lines on stdout; exit codes as in
org.websnake.vidchain.engine.ExitCodes (0 ok, 10 unsupported, 11 unavailable, 12 login, 13 geo, 14 network,
15 http, 16 no media, 2 usage)."""
__version__ = "1"

OK, USAGE, UNSUPPORTED, UNAVAILABLE, LOGIN, GEO, NETWORK, HTTP, NO_MEDIA = 0, 2, 10, 11, 12, 13, 14, 15, 16
