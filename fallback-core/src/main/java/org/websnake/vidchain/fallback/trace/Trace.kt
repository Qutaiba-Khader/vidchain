package org.websnake.vidchain.fallback.trace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One trace event. Every string field passes through [Redactor] before it is written anywhere. */
data class TraceEvent(
	val event: String,                 // e.g. decision, attempt.start, attempt.end, failure, chain.step
	val downloadId: String? = null,
	val chain: String? = null,         // B1..B6
	val step: Int? = null,             // position in the chain
	val method: String? = null,        // O, R, S, Y, C, ...
	val result: String? = null,        // ok / failed / skipped / ...
	val failureClass: String? = null,  // TerminalClassifier class
	val reason: String? = null,
	val durationMs: Long? = null,
	val timeMs: Long = System.currentTimeMillis(),
)

/** Where trace lines go (logcat, the rotating file, tests). */
fun interface TraceSink {
	fun write(line: String)
}

/**
 * The one central trace logger for all VidChain fallback code (INBOX #3). Original upstream code never logs
 * through here except via the allowed one-line seams.
 *
 *     Trace.event { TraceEvent("attempt.start", downloadId = id, chain = "B4", step = 2, method = "O") }
 *
 * The lambda is evaluated only when tracing is on; when it is off the call does nothing.
 */
object Trace {
	private val sinks = java.util.concurrent.CopyOnWriteArrayList<TraceSink>()

	val isEnabled: Boolean get() = TraceConfig.enabled

	inline fun event(build: () -> TraceEvent) {
		if (!TraceConfig.enabled) return
		emit(build())
	}

	fun emit(e: TraceEvent) {
		if (!TraceConfig.enabled) return
		val line = format(e)
		for (s in sinks) runCatching { s.write(line) }
	}

	fun addSink(s: TraceSink) { sinks.add(s) }
	fun removeSink(s: TraceSink) { sinks.remove(s) }
	fun clearSinks() { sinks.clear() }

	/** Runtime override (the Settings switch). Persisting it is the caller's job (TraceAndroid). */
	fun setEnabled(on: Boolean) { TraceConfig.enabled = on }

	/** One line per event: `<UTC time> event=... id=... chain=... step=... method=... result=... class=... dur=...ms reason="..."` */
	fun format(e: TraceEvent): String = buildString {
		append(TIME.get()!!.format(Date(e.timeMs)))
		append(" event=").append(Redactor.clean(e.event))
		e.downloadId?.let { append(" id=").append(Redactor.clean(it)) }
		e.chain?.let { append(" chain=").append(Redactor.clean(it)) }
		e.step?.let { append(" step=").append(it) }
		e.method?.let { append(" method=").append(Redactor.clean(it)) }
		e.result?.let { append(" result=").append(Redactor.clean(it)) }
		e.failureClass?.let { append(" class=").append(Redactor.clean(it)) }
		e.durationMs?.let { append(" dur=").append(it).append("ms") }
		e.reason?.let { append(" reason=\"").append(Redactor.clean(it)?.replace("\"", "'")).append('"') }
	}

	private val TIME = object : ThreadLocal<SimpleDateFormat>() {
		override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
			.apply { timeZone = TimeZone.getTimeZone("UTC") }
	}
}
