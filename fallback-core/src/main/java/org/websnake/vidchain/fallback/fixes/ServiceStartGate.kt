package org.websnake.vidchain.fallback.fixes

/**
 * T9 priority 0b (owner's bug report 2026-10-14): the inherited foreground service started itself in a loop
 * (4,105 starts in 80 s on an S25 Ultra, Android 17) until system_server ran out of memory and the phone soft-rebooted.
 *
 * This gate decides whether the app may ask Android for its foreground service now. It is the safety net under the
 * service fix (no self-restart, startForeground first): even a future bug that asks in a loop gets at most
 * [maxPerWindow] asks per [windowMs], one per [minGapMs], and none for [refusedPauseMs] after Android refused one.
 * Android resets the dataSync time budget when the user brings the app to the foreground, so [appVisible] ends the
 * refusal pause; it never resets the window cap. The state is saved (the flood came right after a process restart),
 * the clock is the wall clock, and a time before the last one (clock moved back) counts as long ago.
 */
class ServiceStartGate(
	private val minGapMs: Long = 10_000,
	private val refusedPauseMs: Long = 10 * 60_000,
	private val windowMs: Long = 10 * 60_000,
	private val maxPerWindow: Int = 10,
) {
	data class State(val lastAskAt: Long = NEVER, val refusedAt: Long = NEVER, val windowStart: Long = NEVER, val asksInWindow: Int = 0)

	enum class Answer { ASK, TOO_SOON, REFUSED_PAUSE, CAP }

	@Volatile var state = State()
		private set

	@Synchronized fun restore(saved: State) { state = saved }

	/** may the app ask now? An ASK is counted at once (the caller asks right after) */
	@Synchronized fun ask(now: Long): Answer {
		val s = state
		if (within(now, s.refusedAt, refusedPauseMs)) return Answer.REFUSED_PAUSE
		if (within(now, s.lastAskAt, minGapMs)) return Answer.TOO_SOON
		val sameWindow = within(now, s.windowStart, windowMs)
		if (sameWindow && s.asksInWindow >= maxPerWindow) return Answer.CAP
		state = s.copy(lastAskAt = now, windowStart = if (sameWindow) s.windowStart else now, asksInWindow = if (sameWindow) s.asksInWindow + 1 else 1)
		return Answer.ASK
	}

	/** Android refused the start or the promotion to the foreground */
	@Synchronized fun refused(now: Long) { state = state.copy(refusedAt = now) }

	/** the user brought the app to the foreground: Android's time budget starts again */
	@Synchronized fun appVisible() { state = state.copy(refusedAt = NEVER, lastAskAt = NEVER) }

	private fun within(now: Long, at: Long, span: Long): Boolean = at != NEVER && now >= at && now - at < span

	companion object { const val NEVER = Long.MIN_VALUE }
}
