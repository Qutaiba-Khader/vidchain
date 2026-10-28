package org.websnake.vidchain.app

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import org.websnake.vidchain.fallback.core.CrashReporting
import org.websnake.vidchain.fallback.trace.Redactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.URLEncoder
import kotlin.system.exitProcess

/**
 * Crash reports for the upstream crash handler (new file, not upstream code; owner report 2026-10-12).
 *
 * The upstream handler saves the crash and then returns. On a background thread that only ends the thread, but on the
 * main thread it leaves the app open with a dead screen thread: the app freezes and Android shows "not responding".
 * [finish] hands a main-thread crash to Android's own handler instead, so the app closes cleanly.
 *
 * [record] keeps the last [KEEP] reports in the app's private folder (always writable, unlike the download folder),
 * every line passed through the trace [Redactor]. Nothing leaves the phone: the crash screen puts the newest report
 * into the GitHub issue text it opens, where the owner sees it before sending.
 */
object VidChainCrashReport {
	private const val KEEP = 5
	private const val MAX_LINES = 60
	private const val ISSUE_CHARS = 3500
	private const val PENDING = "pending"
	private const val ISSUE_ENCODED = 6000
	private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
	private val PATH = Regex("""/(?:storage|sdcard|mnt|data/user|data/data|data/media)/[^\s:'"()]+""")

	@Volatile private var systemHandler: Thread.UncaughtExceptionHandler? = null

	/**
	 * Called once at app start, before any screen installs the upstream handler: remembers Android's own handler.
	 * Owner 2026-10-28: with Firebase in the build that handler is Crashlytics' (installed before the app starts); it
	 * then receives a redacted copy of the crash ([CrashReporting.redact]: no token, link, address or path), also when
	 * a crash comes before the upstream handler is in place.
	 */
	fun captureSystemHandler(redact: Boolean = false) {
		if (systemHandler != null) return
		val below = Thread.getDefaultUncaughtExceptionHandler()
		systemHandler = if (redact && below != null) Redacting(below) else below
		if (redact && below != null) Thread.setDefaultUncaughtExceptionHandler(systemHandler)
	}

	private class Redacting(private val below: Thread.UncaughtExceptionHandler) : Thread.UncaughtExceptionHandler {
		override fun uncaughtException(thread: Thread, error: Throwable) {
			below.uncaughtException(thread, runCatching { CrashReporting.redact(error) }.getOrDefault(error))
		}
	}

	fun record(context: Context, thread: Thread, error: Throwable) {
		runCatching {
			val dir = File(context.filesDir, "crash").apply { mkdirs() }
			val now = Date()
			val text = buildString {
				appendLine("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now))
				appendLine("app: " + appVersion(context))
				appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
				appendLine("thread: ${thread.name}" + if (isMain(thread)) " (main)" else "")
				error.stackTraceToString().lineSequence().take(MAX_LINES).forEach { appendLine(scrub(Redactor.clean(it).orEmpty())) }
			}
			// named in UTC so "newest" holds across time-zone and daylight-saving changes
			val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
			File(dir, "crash_$name.txt").writeText(text)
			// the upstream crash flag is saved in the background, which a main-thread crash no longer waits for
			if (isMain(thread)) File(dir, PENDING).writeText("1")
			// owner 2026-10-28: the upstream handler keeps the app open after a crash on another thread, so Crashlytics
			// would never see it: reported as a non-fatal error (a main-thread crash reaches Crashlytics via [finish])
			else VidChainLog.error(error, "uncaught on thread ${thread.name}")
			dir.listFiles { f -> f.name.startsWith("crash_") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
		}
	}

	/** True once after a main-thread crash (the launcher then opens the crash screen, like the upstream flag). */
	fun takePending(context: Context): Boolean = runCatching { File(File(context.filesDir, "crash"), PENDING).delete() }.getOrDefault(false)

	/** The newest report, shortened so the encoded GitHub issue address stays short enough; null when there is none. */
	fun latestForIssue(context: Context): String? = runCatching {
		var s = File(context.filesDir, "crash").listFiles { f -> f.name.startsWith("crash_") }
			?.maxByOrNull { it.name }?.readText()?.take(ISSUE_CHARS) ?: return@runCatching null
		while (s.length > 200 && URLEncoder.encode(s, "UTF-8").length > ISSUE_ENCODED) s = s.take(s.length * 3 / 4)
		s
	}.getOrNull()

	/** a report is meant for a public issue: e-mail addresses and file paths (video titles) are not needed there */
	private fun scrub(line: String): String = line
		.replace(EMAIL, "<email>")
		.replace(PATH, "<path>")

	/** Last step of the upstream handler: a main-thread crash goes to Android's handler (the app closes, no freeze). */
	fun finish(thread: Thread, error: Throwable) {
		if (!isMain(thread)) return
		val system = systemHandler
		if (system != null && system !is app.core.AIOCrashHandler) {
			system.uncaughtException(thread, error)
		} else {
			Process.killProcess(Process.myPid())
			exitProcess(10)
		}
	}

	private fun isMain(thread: Thread) = thread === Looper.getMainLooper().thread

	private fun appVersion(context: Context): String = runCatching {
		val info = context.packageManager.getPackageInfo(context.packageName, 0)
		"${info.versionName} (${info.longVersionCode})"
	}.getOrDefault("?")
}
