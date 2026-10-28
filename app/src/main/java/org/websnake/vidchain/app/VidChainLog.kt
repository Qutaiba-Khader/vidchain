package org.websnake.vidchain.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.websnake.vidchain.fallback.core.CrashReporting
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceSink

/**
 * Firebase Crashlytics + Analytics (owner, 2026-10-28; new file, not upstream code). ONE switch ([setEnabled], the
 * "Send crash reports and app statistics" row in VidChain settings, default on) turns every crash report, error
 * report, log line and statistic on or off together.
 *
 *   VidChainLog.log("picker opened")              // a line kept with the next report (the last 64 KB)
 *   VidChainLog.error(e, "share look-up")         // a non-fatal error report
 *   VidChainLog.key("method", "H")                // a custom value shown on every later report
 *
 * Nothing happens in a build without app/google-services.json ([available] false; CI writes the file from the
 * GOOGLE_SERVICES_JSON secret). Everything sent goes through [CrashReporting.redact] / [CrashReporting.clean]: no
 * cookie, login, token, signed link, e-mail address or file path ever leaves the phone. Fatal crashes reach
 * Crashlytics through [VidChainCrashReport] (also redacted).
 */
object VidChainLog : CrashReporting.Hooks {
	private const val PREFS = "vidchain_crash_reports"
	private const val KEY_ON = "on"

	@Volatile private var on = true
	@Volatile private var ready = false
	@Volatile private var appContext: Context? = null

	/** once, at app start (the start seam), before the crash handlers are put in place */
	fun init(context: Context) {
		val app = context.applicationContext
		appContext = app
		on = runCatching { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true) }.getOrDefault(true)
		ready = com.aio.BuildConfig.VIDCHAIN_FIREBASE && runCatching { FirebaseApp.getApps(app).isNotEmpty() }.getOrDefault(false)
		applySwitch()
		CrashReporting.hooks = this
		// owner 2026-10-28, generous logging at first: EVERY trace event (redacted by the trace format) is a breadcrumb of
		// the next report, also while the trace on this phone is off; failures and errors are non-fatal reports too
		if (ready) {
			Trace.remote = if (on) remoteSink else null
			key("trace_on", runCatching { org.websnake.vidchain.fallback.trace.TraceConfig.enabled.toString() }.getOrDefault("?"))
			key("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
			key("build_type", if (com.aio.BuildConfig.DEBUG) "debug" else "release")
		}
	}

	/** a trace line: a breadcrumb, and a non-fatal report when it is an error, a failed download or an exhausted chain */
	private fun traceLine(raw: String) {
		val c = crashlytics() ?: return
		runCatching {
			// review run 34: the trace redacts secrets only; a report also drops addresses, link paths and file names
			val line = CrashReporting.clean(raw)
			c.log(line)
			val event = EVENT.find(line)?.groupValues?.get(1) ?: return
			val result = RESULT.find(line)?.groupValues?.get(1)
			// review run 34: each kind once per app run (an error repeated every observer tick is one report)
			if ((event in NONFATAL_EVENTS || (event == "share.rescue" && result == "error")) && reported.add("$event/$result")) {
				// grouped in Crashlytics by the event name (the one stack frame); the line is the message
				val e = TraceReport(line.substringAfter(' '))
				e.stackTrace = arrayOf(StackTraceElement("vidchain.trace", event.replace('.', '_') + (result?.let { "_$it" } ?: ""), "trace", 1))
				c.recordException(e)
			}
		}
	}

	class TraceReport(message: String) : RuntimeException(message)

	private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

	private val remoteSink = TraceSink { line -> if (on) traceLine(line) }

	private val EVENT = Regex("""\bevent=([a-z.]+)""")
	private val RESULT = Regex("""\bresult=([a-z-]+)""")
	private val NONFATAL_EVENTS = setOf("failure", "chain.exhausted", "hedge.exhausted", "runtime.error", "observer.error", "seam.error", "watchdog.kill")

	override fun available(): Boolean = ready
	override fun enabled(): Boolean = on

	override fun setEnabled(on: Boolean) {
		val was = this.on
		this.on = on
		runCatching { appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_ON, on)?.apply() }
		// a crash caught while the switch was off stays on the phone: dropped before collection starts again
		if (on && !was) runCatching { crashlytics()?.deleteUnsentReports() }
		applySwitch()
		if (ready) Trace.remote = if (on) remoteSink else null
	}

	fun log(message: String) {
		if (!on) return
		crashlytics()?.log(CrashReporting.clean(message))
	}

	fun error(t: Throwable, note: String? = null) {
		if (!on) return
		val c = crashlytics() ?: return
		runCatching {
			note?.let { c.log(CrashReporting.clean(it)) }
			c.recordException(CrashReporting.redact(t))
		}
	}

	fun key(name: String, value: String) {
		if (!on) return
		runCatching { crashlytics()?.setCustomKey(name, CrashReporting.clean(value)) }
	}

	override fun testCrash() {
		if (!ready || !on) return
		error(RuntimeException("VidChain test report (non-fatal)"))
		Handler(Looper.getMainLooper()).post { throw RuntimeException("VidChain test crash") }
	}

	/** the one switch: Crashlytics collection and Analytics collection together; off also drops reports not sent yet
	 *  (every start too, so a crash caught while off is never sent) */
	private fun applySwitch() {
		if (!ready) return
		val app = appContext ?: return
		runCatching {
			val c = FirebaseCrashlytics.getInstance()
			c.isCrashlyticsCollectionEnabled = on
			if (!on) c.deleteUnsentReports()
		}
		runCatching { FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(on) }
	}

	private fun crashlytics(): FirebaseCrashlytics? = if (ready) runCatching { FirebaseCrashlytics.getInstance() }.getOrNull() else null
}
