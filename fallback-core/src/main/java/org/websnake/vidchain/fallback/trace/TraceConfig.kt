package org.websnake.vidchain.fallback.trace

/**
 * THE trace-log master switch (owner decision INBOX #3).
 *
 * [DEFAULT_ENABLED] is the one flag: it was ON during development and is OFF from v1.0.0 (the owner's rule: ship with
 * tracing OFF by changing only this line).
 * The Settings switch (Settings -> VidChain fallbacks -> Trace log) stores the owner's choice and wins over the
 * default on that phone. When tracing is off, nothing is logged, no file is written and every call site returns
 * at once without building its message.
 */
object TraceConfig {
	const val DEFAULT_ENABLED: Boolean = false

	/** logcat tag of every trace line */
	const val TAG = "VidChainTrace"

	/** the trace file rotates at this size and keeps this many files (trace.log, trace.1.log, ...) */
	const val MAX_FILE_BYTES: Long = 512L * 1024L
	const val MAX_FILES: Int = 4

	/** SharedPreferences file/key holding the Settings switch */
	const val PREFS = "vidchain_trace"
	const val PREF_ENABLED = "enabled"

	@Volatile
	var enabled: Boolean = DEFAULT_ENABLED
		internal set
}
