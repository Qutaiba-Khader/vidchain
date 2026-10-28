package org.websnake.vidchain.fallback.core

import org.websnake.vidchain.fallback.trace.Redactor

/**
 * Crash reports (owner, 2026-10-28): Firebase Crashlytics + Analytics live in the app module, the only one that carries
 * the Firebase configuration; VidChain's settings screen and the adb test receiver reach them through [hooks]. One
 * switch turns every report, log and statistic on or off. Everything that leaves the phone goes through [redact] /
 * [clean] first: no cookie, login, token or signed-link value (the trace rule), no e-mail address, no file path (a
 * path names the video).
 */
object CrashReporting {
	interface Hooks {
		/** this build carries a Firebase configuration (app/google-services.json was present when it was built) */
		fun available(): Boolean
		fun enabled(): Boolean
		fun setEnabled(on: Boolean)
		/** a non-fatal test report, then a real crash on the main thread (Crashlytics sends it on the next start) */
		fun testCrash()
	}

	@Volatile var hooks: Hooks? = null

	private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
	private val PATH = Regex("""/(?:storage|sdcard|mnt|data/user|data/data|data/media)/[^\s:'"()]+""")
	private const val MAX_DEPTH = 8

	/** a log line or a custom value as it may leave the phone */
	fun clean(s: String?): String = Redactor.clean(s).orEmpty().replace(EMAIL, "<email>").replace(PATH, "<path>")

	/**
	 * A copy of [t] for a report: every message cleaned, the stack frames kept (Crashlytics groups by them). The copy's
	 * message starts with the original class name, since a report shows the copy's own class.
	 */
	fun redact(t: Throwable): Throwable = copy(t, 0, java.util.IdentityHashMap())

	private fun copy(t: Throwable, depth: Int, seen: java.util.IdentityHashMap<Throwable, Throwable>): Throwable {
		seen[t]?.let { return it }
		val msg = t.message?.let { clean(it) }
		val c = RedactedThrowable(t.javaClass.name + (msg?.let { ": $it" } ?: ""))
		seen[t] = c
		c.stackTrace = t.stackTrace
		if (depth < MAX_DEPTH) {
			t.cause?.takeIf { it !== t }?.let { c.initCause(copy(it, depth + 1, seen)) }
			for (s in t.suppressed) c.addSuppressed(copy(s, depth + 1, seen))
		}
		return c
	}

	class RedactedThrowable(message: String) : RuntimeException(message)
}
