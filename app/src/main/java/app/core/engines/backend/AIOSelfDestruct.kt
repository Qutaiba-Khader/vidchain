package app.core.engines.backend

/**
 * BUILD FIX (see BUILD-FIXES.md): upstream keeps this class out of git. Upstream uses it as a
 * remote kill switch checked on every screen; here it never does anything.
 */
object AIOSelfDestruct {
	@JvmStatic
	fun shouldSelfDestructApplication() = Unit
}
