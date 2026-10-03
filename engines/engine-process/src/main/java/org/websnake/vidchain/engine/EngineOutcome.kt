package org.websnake.vidchain.engine

/** How one engine process ended. Only [Crashed] counts against an engine (quarantine); an OS kill never does. */
sealed class EngineOutcome {
	object Success : EngineOutcome()
	data class Failed(val code: Int, val reason: String) : EngineOutcome()
	data class Unsupported(val reason: String) : EngineOutcome()
	object Cancelled : EngineOutcome()
	object TimedOut : EngineOutcome()
	/** killed from outside (phantom-process killer, low memory, FGS limit): re-queue, never a crash */
	data class OsKilled(val reason: String) : EngineOutcome()
	data class Crashed(val signal: Int) : EngineOutcome()

	override fun toString(): String = when (this) {
		Success -> "success"; Cancelled -> "cancelled"; TimedOut -> "timed-out"
		is Failed -> "failed($code: $reason)"; is Unsupported -> "unsupported($reason)"
		is OsKilled -> "os-killed($reason)"; is Crashed -> "crashed(signal $signal)"
	}
}

/**
 * The exit-code table. Our own engines (the aio_engine Python launcher, T5.3) use the codes below; other tools only
 * use 0 / non-zero. A signal shows up as 128 + signal number (reported by the launcher shell).
 */
object ExitCodes {
	const val OK = 0
	const val USAGE = 2
	const val UNSUPPORTED = 10       // this engine cannot handle the URL
	const val UNAVAILABLE = 11       // content removed / private / not found
	const val LOGIN_REQUIRED = 12
	const val GEO_BLOCKED = 13
	const val NETWORK = 14
	const val HTTP_ERROR = 15
	const val NO_MEDIA = 16          // page parsed, nothing downloadable found

	const val SIGNAL_BASE = 128
	const val SIGILL = 4; const val SIGABRT = 6; const val SIGBUS = 7; const val SIGFPE = 8
	const val SIGKILL = 9; const val SIGSEGV = 11; const val SIGTERM = 15; const val SIGSYS = 31

	private val CRASH_SIGNALS = setOf(SIGILL, SIGABRT, SIGBUS, SIGFPE, SIGSEGV, SIGSYS)
	/** what the system sends to a process it reclaims; every other code above 128 can also be a program's own exit
	 *  code (ffmpeg exits with AVERROR & 0xFF: 183 invalid data, 146 timeout, 145 refused, ...) */
	private val KILL_SIGNALS = setOf(SIGKILL, SIGTERM)

	/**
	 * @param exitCode the code the launcher shell reported, or null when the shell itself died (whole group killed)
	 * @param cancelledByUs we killed it because the caller cancelled
	 * @param timedOut we killed it because it ran past its time limit
	 */
	fun classify(exitCode: Int?, cancelledByUs: Boolean, timedOut: Boolean, stderrTail: String = ""): EngineOutcome {
		if (timedOut) return EngineOutcome.TimedOut
		if (cancelledByUs) return EngineOutcome.Cancelled
		if (exitCode == null) return EngineOutcome.OsKilled("process group killed from outside")
		if (exitCode == OK) return EngineOutcome.Success
		if (exitCode > SIGNAL_BASE && exitCode < SIGNAL_BASE + 64) {
			val sig = exitCode - SIGNAL_BASE
			if (sig in CRASH_SIGNALS) return EngineOutcome.Crashed(sig)
			if (sig in KILL_SIGNALS) return EngineOutcome.OsKilled("signal $sig")
		}
		val reason = stderrTail.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(300) ?: "exit $exitCode"
		return when (exitCode) {
			UNSUPPORTED -> EngineOutcome.Unsupported(reason)
			127 -> EngineOutcome.Unsupported("engine binary not found")
			126 -> EngineOutcome.Unsupported("engine binary not executable")
			else -> EngineOutcome.Failed(exitCode, reason)
		}
	}
}
