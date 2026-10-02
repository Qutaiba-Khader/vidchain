package app.core.engines.backend

/**
 * BUILD FIX (see BUILD-FIXES.md): upstream keeps this class out of git. Upstream sends crash
 * reports, user feedback and a log of every finished download to its Parse server; here
 * every call is a no-op and nothing is sent.
 */
class AIOBackend {
	fun initParseBackend() = Unit
	fun saveAppCrashedInfo(@Suppress("UNUSED_PARAMETER") info: Any?) = Unit
	fun saveDownloadLog(@Suppress("UNUSED_PARAMETER") download: Any?) = Unit
	fun saveUserFeedback(@Suppress("UNUSED_PARAMETER") message: Any?) = Unit
}
