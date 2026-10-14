package org.websnake.vidchain.app

import android.content.Context
import org.websnake.vidchain.fallback.fixes.ServiceStartGate
import org.websnake.vidchain.fallback.fixes.ServiceStartGate.Answer
import org.websnake.vidchain.fallback.fixes.ServiceStartGate.Companion.NEVER
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent

/**
 * T9 priority 0b: every request for the app's foreground service goes through [ServiceStartGate], whose state is kept
 * in the app's preferences so a restarted process cannot start a new flood (owner's S25 Ultra soft-reboot,
 * 2026-10-14). Asks and refusals go to the trace; skipped asks do not (they come every few seconds while a refusal
 * pause lasts).
 */
object VidChainServiceStarts {
	private const val PREFS = "vidchain-fgs-gate"
	private val gate = ServiceStartGate()
	@Volatile private var loaded = false

	@JvmStatic
	fun mayAsk(context: Context): Boolean {
		load(context)
		val answer = gate.ask(System.currentTimeMillis())
		if (answer != Answer.ASK) return false
		save(context)
		Trace.event { TraceEvent("fgs", result = "asked") }
		return true
	}

	@JvmStatic
	fun refused(context: Context, error: Throwable) {
		load(context)
		gate.refused(System.currentTimeMillis())
		save(context)
		Trace.event { TraceEvent("fgs", result = "refused", reason = error.javaClass.simpleName) }
	}

	/** Android stopped the service at the end of its time budget: no new ask until the app is visible again */
	@JvmStatic
	fun timedOut(context: Context) {
		load(context)
		gate.refused(System.currentTimeMillis())
		save(context)
		Trace.event { TraceEvent("fgs", result = "timeout") }
	}

	@JvmStatic
	fun started(context: Context) {
		load(context)
		gate.promoted()
		save(context)
		Trace.event { TraceEvent("fgs", result = "foreground") }
	}

	/** the user brought the app to the foreground: Android's dataSync budget starts again */
	@JvmStatic
	fun appVisible(context: Context) {
		load(context)
		if (gate.state.refusedAt == NEVER && gate.state.lastAskAt == NEVER) return
		gate.appVisible()
		save(context)
	}

	private fun load(context: Context) {
		if (loaded) return
		synchronized(this) {
			if (loaded) return
			runCatching {
				val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				gate.restore(ServiceStartGate.State(p.getLong("ask", NEVER), p.getLong("refused", NEVER), p.getLong("window", NEVER), p.getInt("asks", 0)))
			}
			loaded = true
		}
	}

	private fun save(context: Context) {
		val s = gate.state
		runCatching {
			context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
				.putLong("ask", s.lastAskAt).putLong("refused", s.refusedAt).putLong("window", s.windowStart).putInt("asks", s.asksInWindow)
				.apply()
		}
	}
}
