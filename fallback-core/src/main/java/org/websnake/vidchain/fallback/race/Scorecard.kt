package org.websnake.vidchain.fallback.race

import org.json.JSONArray
import org.json.JSONObject

/**
 * T9 decision 1 (adhd 2026-10-14): the learned order of the methods, per site.
 *
 * - Each (site, method) keeps its last [keep] results, each with a cause and its duration.
 * - Methods are ranked by expected time to success (typical time / success rate, recent results weigh more).
 * - Only method faults move a method down; a rate limit (429) or being offline pauses the site instead, and a login
 *   wall or a geo block blames nobody (the user gets a hint).
 * - [FAULTS_TO_BACK] faults in a row move a method to the back; it gets one new try every [retryAfterMs], so a site
 *   that changes heals itself.
 * - A site's own row decides only after [ownRowAfter] results; until then the seed row (from the site x method test
 *   matrix shipped with the app), else the generic row that unknown sites share, else the seed order itself.
 * - Stored: the site name, the method id, the cause and the time - never an address, a cookie or a token.
 * Pure (JVM-tested, ScorecardTest); the caller persists [toJson].
 */
class Scorecard(
	private val clock: () -> Long = System::currentTimeMillis,
	private val keep: Int = 16,
	private val ownRowAfter: Int = 6,
	private val retryAfterMs: Long = 3 * 24 * 3_600_000L,
	private val seeds: Map<String, List<String>> = emptyMap(),
) {
	enum class Cause(val counts: Boolean, val fault: Boolean) {
		OK(true, false),
		NOT_UNDERSTOOD(true, true),      // the page or answer was not understood
		EMPTY(true, true),               // the method found nothing
		TIMEOUT(true, true),
		NOT_MEDIA(true, true),           // a candidate that failed the proof
		LOGIN(false, false),             // a login wall: a hint for the user, no blame
		GEO(false, false),               // blocked in this country: a hint, no blame
		RATE_LIMITED(false, false),      // 429: pauses the site, no blame
		OFFLINE(false, false),           // no network: pauses the site, no blame
		CANCELLED(false, false),         // another method won first
	}

	data class Result(val cause: Cause, val ms: Long, val atMs: Long)

	private val rows = HashMap<String, HashMap<String, ArrayDeque<Result>>>()
	private val pausedUntil = HashMap<String, Long>()

	@Synchronized
	fun record(site: String, method: String, cause: Cause, ms: Long) {
		val now = clock()
		for (key in listOf(site, GENERIC).distinct()) {
			if (key == GENERIC && site in seeds) continue                 // a seeded site does not shape the generic row
			val q = rows.getOrPut(key) { HashMap() }.getOrPut(method) { ArrayDeque() }
			q.addLast(Result(cause, ms.coerceAtLeast(0), now))
			while (q.size > keep) q.removeFirst()
		}
		if (cause == Cause.RATE_LIMITED || cause == Cause.OFFLINE) pausedUntil[site] = now + PAUSE_MS
	}

	/** a 429 or "offline" was seen on [site] a moment ago: wait instead of trying more methods there */
	@Synchronized
	fun pausedFor(site: String): Long = ((pausedUntil[site] ?: 0L) - clock()).coerceAtLeast(0L)

	/** [methods] in the order to try them on [site] (only the methods given; any not known keep the seed order) */
	@Synchronized
	fun rank(site: String, methods: List<String>): List<String> {
		val base = seedOrder(site, methods)
		val row = rowFor(site) ?: return base
		val now = clock()
		data class Scored(val id: String, val score: Double, val back: Boolean)
		val scored = base.mapIndexed { i, id ->
			val q = row[id].orEmpty().toList()
			val counted = q.filter { it.cause.counts }
			val prior = (i + 1) * PRIOR_STEP_MS
			if (counted.size < 2) return@mapIndexed Scored(id, prior, false)
			var wOk = 0.0; var wAll = 0.0; var wMs = 0.0; var wMsN = 0.0
			counted.asReversed().forEachIndexed { age, r ->
				val w = Math.pow(DECAY, age.toDouble())
				wAll += w
				if (r.cause == Cause.OK) { wOk += w; wMs += w * r.ms; wMsN += w }
			}
			val rate = (wOk / wAll).coerceAtLeast(MIN_RATE)
			val typical = if (wMsN > 0) wMs / wMsN else prior
			val streak = counted.takeLastWhile { it.cause.fault }.size
			val failing = streak >= FAULTS_TO_BACK
			val back = failing && now - counted.last().atMs < retryAfterMs
			// a failing method whose rest is over gets its new try at its seed place, not behind everything
			Scored(id, if (failing && !back) prior else typical / rate, back)
		}
		return scored.sortedWith(compareBy<Scored>({ it.back }, { it.score })).map { it.id }
	}

	/** the time [method] usually needs on [site] to prove its first bytes (p90 of its successes); [DEFAULT_PROOF_MS] with no data */
	@Synchronized
	fun proofTimeMs(site: String, method: String): Long {
		for (key in listOf(site, GENERIC)) {
			val ok = rows[key]?.get(method).orEmpty().filter { it.cause == Cause.OK }.map { it.ms }.sorted()
			if (ok.size >= 3) return ok[((ok.size - 1) * 0.9).toInt()].coerceIn(MIN_PROOF_MS, MAX_PROOF_MS)
		}
		return DEFAULT_PROOF_MS
	}

	private fun rowFor(site: String): Map<String, ArrayDeque<Result>>? {
		val own = rows[site]
		if (own != null && own.values.sumOf { q -> q.count { it.cause.counts } } >= ownRowAfter) return own
		if (site in seeds) return null                                      // the seed order until the site's own row is ready
		val generic = rows[GENERIC]
		return if (generic != null && generic.values.sumOf { q -> q.count { it.cause.counts } } >= ownRowAfter) generic else null
	}

	private fun seedOrder(site: String, methods: List<String>): List<String> {
		val seed = seeds[site] ?: seeds[GENERIC] ?: return methods
		return seed.filter { it in methods } + methods.filter { it !in seed }
	}

	@Synchronized
	fun toJson(): String = JSONObject().apply {
		for ((site, ms) in rows) put(site, JSONObject().apply {
			for ((m, q) in ms) put(m, JSONArray().apply { q.forEach { put(JSONArray().put(it.cause.name).put(it.ms).put(it.atMs)) } })
		})
	}.toString()

	@Synchronized
	fun load(json: String) {
		runCatching {
			val o = JSONObject(json)
			for (site in o.keys()) {
				val ms = o.getJSONObject(site)
				val row = rows.getOrPut(site) { HashMap() }
				for (m in ms.keys()) {
					val a = ms.getJSONArray(m)
					val q = ArrayDeque<Result>()
					for (i in 0 until a.length()) {
						val r = a.getJSONArray(i)
						val cause = runCatching { Cause.valueOf(r.getString(0)) }.getOrNull() ?: continue
						q.addLast(Result(cause, r.getLong(1), r.getLong(2)))
					}
					while (q.size > keep) q.removeFirst()
					row[m] = q
				}
			}
		}
	}

	companion object {
		const val GENERIC = "*"
		const val DEFAULT_PROOF_MS = 4_000L
		const val MIN_PROOF_MS = 1_500L
		const val MAX_PROOF_MS = 30_000L
		const val PAUSE_MS = 60_000L
		const val FAULTS_TO_BACK = 3
		private const val DECAY = 0.85
		private const val MIN_RATE = 0.05
		private const val PRIOR_STEP_MS = 5_000.0

		/** the site a link belongs to: its host without "www." / "m." / "mobile." (never the path or the query) */
		fun siteOf(url: String): String {
			val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return GENERIC
			return host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
		}
	}
}
