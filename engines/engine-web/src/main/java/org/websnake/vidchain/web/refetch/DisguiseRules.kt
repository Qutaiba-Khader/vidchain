package org.websnake.vidchain.web.refetch

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Which static-looking addresses may really be stream playlists (T8.12, owner decision; idea from Web Video Caster's
 * "disguised playlist" rules, but VidChain's own generic rules - no vendor's per-site list). Pure, JVM-tested.
 *
 * Two sources: the seed table (assets/vidchain-disguise-rules.json, generic path shapes) and rules LEARNED from
 * evidence - the in-page probe or the re-fetcher saw #EXTM3U / <MPD at a static-looking address, so its siblings
 * (same host, extension and path shape) are checked too. A learned rule lives [LEARNED_TTL_MS] after its last
 * confirmation. Any rule shape that answers [DEMOTE_AFTER] times with something that is not a playlist is dropped
 * for the session, so a too-broad rule cannot keep re-fetching every picture of a heavy page.
 */
class DisguiseRules(json: String) {
	private class Rule(val name: String, val path: Regex?, val query: Regex?)

	private val staticExt: Set<String>
	private val rules: List<Rule>

	init {
		val o = JSONObject(json)
		val ext = o.getJSONArray("static_extensions")
		staticExt = (0 until ext.length()).map { ext.getString(it).lowercase(Locale.ROOT) }.toSet()
		val r = o.getJSONArray("rules")
		rules = (0 until r.length()).map { i ->
			val x = r.getJSONObject(i)
			Rule(x.getString("name"), x.optString("path").takeIf { it.isNotEmpty() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
				x.optString("query").takeIf { it.isNotEmpty() }?.let { Regex(it, RegexOption.IGNORE_CASE) })
		}
	}

	private val learned = LinkedHashMap<String, Long>()          // shape -> valid until
	private val misses = HashMap<String, Int>()                    // shape -> answers that were not playlists
	private val demoted = HashSet<String>()

	/** the address's extension when it is one of the static ones, else null */
	fun staticExtension(url: String): String? = extension(url)?.takeIf { it in staticExt }

	/** the rule that makes this static-looking address worth a look, or null */
	@Synchronized
	fun match(url: String, now: Long = System.currentTimeMillis()): String? {
		if (staticExtension(url) == null) return null
		val shape = shape(url) ?: return null
		if (shape in demoted) return null
		learned[shape]?.let { until -> if (until > now) return "learned" else learned.remove(shape) }
		val u = runCatching { URI(url) }.getOrNull() ?: return null
		val path = u.rawPath.orEmpty(); val query = u.rawQuery.orEmpty()
		return rules.firstOrNull { r -> (r.path?.containsMatchIn(path) ?: true) && (r.query?.containsMatchIn(query) ?: true) && (r.path != null || r.query != null) }?.name
	}

	/** evidence: [url] answered with a playlist; its siblings are checked from now on */
	@Synchronized
	fun learn(url: String, now: Long = System.currentTimeMillis()) {
		if (staticExtension(url) == null) return
		val shape = shape(url) ?: return
		demoted.remove(shape); misses.remove(shape)
		learned.remove(shape)
		learned[shape] = now + LEARNED_TTL_MS
		while (learned.size > MAX_LEARNED) learned.remove(learned.keys.first())
	}

	/** a checked address was not a playlist */
	@Synchronized
	fun miss(url: String) {
		val shape = shape(url) ?: return
		val n = (misses[shape] ?: 0) + 1
		misses[shape] = n
		if (n >= DEMOTE_AFTER) { demoted += shape; learned.remove(shape); misses.remove(shape) }
		if (misses.size > 2_000) misses.clear()
	}

	companion object {
		const val LEARNED_TTL_MS = 30 * 60_000L
		const val DEMOTE_AFTER = 3
		private const val MAX_LEARNED = 64

		fun extension(url: String): String? {
			val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
			val last = path.substringAfterLast('/')
			return last.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && it.length <= 5 && '.' in last }
		}

		/**
		 * host + path with the changing parts (numbers, long hex, ids with digits) and the file name's stem folded, plus
		 * the extension, e.g. cdn.example/hls/<n>/<id>/ + any name + .jpg. Siblings of one stream share it; the query is left out.
		 */
		fun shape(url: String): String? {
			val u = runCatching { URI(url) }.getOrNull() ?: return null
			val host = u.host?.lowercase(Locale.ROOT) ?: return null
			val parts = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
			if (parts.isEmpty()) return null
			fun fold(p: String) = when {
				p.all { it.isDigit() } -> "<n>"
				p.length >= 8 && p.any { it.isDigit() } -> "<id>"
				else -> p.lowercase(Locale.ROOT)
			}
			// a file name is folded to its extension; an address without one keeps its (folded) last part
			val last = extension(url)?.let { "*.$it" } ?: fold(parts.last())
			return host + "/" + (parts.dropLast(1).map(::fold) + last).joinToString("/")
		}
	}
}
