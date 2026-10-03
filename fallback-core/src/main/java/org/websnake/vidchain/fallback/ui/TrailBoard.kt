package org.websnake.vidchain.fallback.ui

import org.websnake.vidchain.fallback.ledger.AttemptRow
import java.util.concurrent.ConcurrentHashMap

/** What the download card shows about a parent's fallback chain. Updated by the coordinator, read by the UI thread. */
data class Trail(
	val parentId: String,
	val chain: String,
	val step: Int,                 // 1-based position among the methods that can run
	val total: Int,
	val method: String,
	val state: State,
) {
	enum class State { RUNNING, WAITING_CHILD, VERIFYING, DELIVERED, BEST_SO_FAR, EXHAUSTED }
}

/** In-memory board (no disk on the UI path). Empty after a restart until the chain moves again; the "methods tried" sheet reads the ledger. */
object TrailBoard {
	private val trails = ConcurrentHashMap<String, Trail>()
	private val childToParent = ConcurrentHashMap<String, String>()
	private val childMethod = ConcurrentHashMap<String, String>()

	fun put(t: Trail) { trails[t.parentId] = t }
	fun update(parentId: String, change: (Trail) -> Trail) { trails.computeIfPresent(parentId) { _, t -> change(t) } }
	fun linkChild(childId: String, parentId: String, method: String) { childToParent[childId] = parentId; childMethod[childId] = method }
	fun methodOfChild(childId: String): String? = childMethod[childId]
	fun of(parentId: String): Trail? = trails[parentId]
	fun parentOf(childId: String): String? = childToParent[childId]
	fun clear() { trails.clear(); childToParent.clear(); childMethod.clear() }
}

/** Card / sheet texts. English for now (the app's own strings stay upstream's). Pure, JVM-tested. */
object TrailText {
	private val NAMES = mapOf(
		"STUB" to "Self-check stub", "C" to "YouTube client retry", "S" to "Session retry", "Y" to "yt-dlp (any URL)",
		"P" to "NewPipe streams", "F" to "ffmpeg", "N" to "yt-dlp nightly", "W" to "Page player catcher", "R" to "Redirect unwrap",
		"L" to "File-host link", "H" to "Page scan", "G" to "gallery-dl", "U" to "you-get", "T" to "Streamlink", "X" to "lux",
		"O" to "Plain download", "A" to "aria2c", "D" to "Android downloader", "M" to "Media3 stream saver",
	)

	/** every method in a stable display order (Settings) */
	val ALL_METHODS: List<String> = NAMES.keys.toList()

	fun name(id: String): String = NAMES[id] ?: id

	fun card(t: Trail): String = when (t.state) {
		Trail.State.RUNNING -> "Fallback: trying ${name(t.method)} (${t.step}/${t.total})"
		Trail.State.WAITING_CHILD -> "Fallback: ${name(t.method)} found a source, downloading (${t.step}/${t.total})"
		Trail.State.VERIFYING -> "Fallback: checking the file from ${name(t.method)}"
		Trail.State.DELIVERED -> "Fallback: saved by ${name(t.method)}"
		Trail.State.BEST_SO_FAR -> "Fallback: kept the file from ${name(t.method)} (could not fully check it)"
		Trail.State.EXHAUSTED -> "Fallback: no other method worked (${t.total} tried)"
	}

	fun childCard(method: String): String = "Fallback download via ${name(method)} (tap for the methods tried)"

	/** pure text of the sheet (JVM-tested) */
	fun methodsTried(rows: List<AttemptRow>, trail: Trail?): String {
		if (rows.isEmpty()) return "No fallback method has run for this download yet."
		return buildString {
			trail?.let { append(card(it)).append("\n\n") }
			for (r in rows) {
				append(r.attemptNo).append(". ").append(name(r.method)).append(" — ").append(r.state.name.lowercase().replace('_', ' '))
				val secs = r.endedMs?.let { (it - r.startedMs).coerceAtLeast(0) / 1000.0 }
				if (secs != null) append(String.format(java.util.Locale.US, " (%.1f s)", secs))
				r.reason?.let { append("\n   ").append(it) }
				append('\n')
			}
		}.trimEnd()
	}

}
