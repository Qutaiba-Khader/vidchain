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
	/** the file kinds a download or a delivery can be (media, pictures, documents, subtitles, archives) */
	private const val EXT = "mp4|mkv|webm|mp3|m4a|aac|opus|ogg|wav|flac|ts|mov|avi|flv|3gp|m3u8|mpd|part|temp|" +
		"jpg|jpeg|png|gif|webp|pdf|srt|vtt|ass|txt|zip|rar|7z|apk|torrent|html|json|xml|csv|gz"
	private const val PHONE_DIRS = "storage|sdcard|mnt|data/user|data/data|data/media|data/local"
	/** review run 35: a phone path up to its file's extension (a file name may hold spaces) ... */
	private val PATH_FILE = Regex("""(?i)/(?:$PHONE_DIRS)/[^:'"()\r\n]*?\.(?:$EXT)\b""")
	/** ... and any other phone path */
	private val PATH = Regex("""/(?:$PHONE_DIRS)/[^\s:'"()]+""")
	/** review runs 34-35: a link keeps only its host (the path names the video); also behind userinfo, and www. links */
	private val LINK = Regex("""(?i)\b([a-z][a-z0-9+.-]*://[^/\s"'?#]+)[^\s"']*""")
	private val WWW = Regex("""(?i)\b(www\.[a-z0-9-]+(?:\.[a-z0-9-]+)+)/[^\s"']*""")
	/** a file name in quotes (it may hold spaces; never a whole "error: ... name" reason) or as one word */
	private val FILE_QUOTED = Regex("""(?i)(["'])[^"':\r\n]*\.($EXT)\1""")
	private val FILE = Regex("""(?i)[^\s/\\"'=:<>]+\.($EXT)\b""")
	private const val MAX_LINE = 1024
	private const val MAX_DEPTH = 8

	/**
	 * a log line or a custom value as it may leave the phone: no secret, address, path, link path or file name. Review
	 * run 35: a whole trace line is cleaned first and shortened last (the trace keeps 300 characters per field).
	 */
	fun clean(s: String?): String = Redactor.clean(s, 8 * MAX_LINE).orEmpty()
		.replace(LINK) { m -> if (m.value.length > m.groupValues[1].length) m.groupValues[1] + "/<path>" else m.value }
		.replace(WWW) { m -> m.groupValues[1] + "/<path>" }
		.replace(EMAIL, "<email>")
		.replace(PATH_FILE, "<path>")
		.replace(PATH, "<path>")
		.replace(FILE_QUOTED) { m -> m.groupValues[1] + "<file>." + m.groupValues[2].lowercase() + m.groupValues[1] }
		.replace(FILE) { m -> "<file>." + m.groupValues[1].lowercase() }
		.take(MAX_LINE)

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
