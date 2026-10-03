package org.websnake.vidchain.engine

import java.util.concurrent.ConcurrentHashMap

/** Small persistent key/value store (SharedPreferences in the app, a map in tests). */
interface KeyValueStore {
	fun get(key: String): String?
	fun put(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
	private val m = ConcurrentHashMap<String, String>()
	override fun get(key: String) = m[key]
	override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

data class Capability(val ok: Boolean, val detail: String)

/**
 * Lazy capability probe: each engine is probed the first time a method needs it, once per engine version (hash),
 * and the answer is remembered. A probe that did not finish (cancelled, OS-killed, timed out) is not cached.
 */
class CapabilityProbe(private val runner: ProcessEngineRunner, private val store: KeyValueStore) {
	suspend fun check(engine: String, engineHash: String, spec: EngineSpec, accept: (EngineResult) -> Capability = ::defaultAccept): Capability {
		val key = "probe.$engine.$engineHash"
		store.get(key)?.let { return Capability(it.startsWith("ok:"), it.substringAfter(':')) }
		val result = runner.run(spec.copy(timeoutMs = minOf(spec.timeoutMs, PROBE_TIMEOUT_MS)))
		val cap = accept(result)
		val transient = result.outcome is EngineOutcome.OsKilled || result.outcome is EngineOutcome.TimedOut || result.outcome is EngineOutcome.Cancelled
		if (!transient) store.put(key, (if (cap.ok) "ok:" else "no:") + cap.detail.take(500))
		return cap
	}

	companion object {
		const val PROBE_TIMEOUT_MS = 30_000L

		fun defaultAccept(r: EngineResult) =
			if (r.outcome == EngineOutcome.Success) Capability(true, r.stdoutTail.firstOrNull().orEmpty()) else Capability(false, r.outcome.toString())

		/** bundled Python 3 with ssl (the library's interpreter) */
		fun pythonSpec(layout: EngineLayout) = EngineSpec(
			listOf(layout.python.absolutePath, "-c", "import sys, ssl; print(sys.version.split()[0]); print(ssl.OPENSSL_VERSION)"), layout.env())

		/** the aio_engine launcher (ships in T5.3) on the bundled Python */
		fun aioEngineSpec(layout: EngineLayout, pythonPath: List<java.io.File>) = EngineSpec(
			listOf(layout.python.absolutePath, "-m", "aio_engine", "probe"), layout.env(extraPythonPath = pythonPath))

		/** the bundled ffmpeg */
		fun ffmpegSpec(layout: EngineLayout) = EngineSpec(listOf(layout.ffmpeg.absolutePath, "-hide_banner", "-version"), layout.env())
	}
}
