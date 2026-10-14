package org.websnake.vidchain.fallback.fetch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/**
 * T9 decision 2 (owner, 2026-10-14): one analysis per link. The `yt-dlp -J` result of a link is kept in memory for a
 * short time and handed to the download, so the download neither loads the page again nor runs yt-dlp's extraction
 * again. Pure (JVM-tested, AnalysisStoreTest).
 *
 * - Keyed by the link plus a cookie generation: a value that changes when the site's cookies change (log in / out),
 *   so a logged-out analysis is never used for a logged-in download. The store never holds a cookie.
 * - At most [capacity] links, each for [ttlMs]; the oldest is dropped first (its file goes with it, [onDrop]).
 * - [analyse] is single-flight: callers asking for the same key while an analysis runs share it (one request to the site).
 * - A record whose download failed with an expired / refused address is [invalidate]d and analysed again once.
 */
class AnalysisStore<R : Any>(
	private val capacity: Int = 16,
	private val ttlMs: Long = 10 * 60_000L,
	private val clock: () -> Long = System::currentTimeMillis,
	private val onDrop: (R) -> Unit = {},
) {
	data class Key(val url: String, val cookieGeneration: String)

	private class Entry<R>(val record: R, val atMs: Long)

	private val entries = LinkedHashMap<Key, Entry<R>>(capacity, 0.75f, true)
	private val inFlight = HashMap<Key, CompletableDeferred<R?>>()
	private val reanalysed = HashSet<Key>()

	/** a fresh record for [key], or null */
	@Synchronized
	fun get(key: Key): R? {
		val e = entries[key] ?: return null
		if (clock() - e.atMs >= ttlMs) {
			entries.remove(key)
			onDrop(e.record)
			return null
		}
		return e.record
	}

	@Synchronized
	fun put(key: Key, record: R) {
		entries.remove(key)?.let { if (it.record !== record) onDrop(it.record) }
		entries[key] = Entry(record, clock())
		while (entries.size > capacity) {
			val oldest = entries.entries.iterator().next()
			entries.remove(oldest.key)
			onDrop(oldest.value.record)
		}
	}

	/**
	 * the download using [key]'s record failed (expired / refused address): drop it. true = analyse again (the first
	 * time for this key); false = it was already analysed again once, so the method race takes over
	 */
	@Synchronized
	fun invalidate(key: Key): Boolean {
		entries.remove(key)?.let { onDrop(it.record) }
		return reanalysed.add(key)
	}

	/** the record for [key]: kept, joined while another caller analyses it, or analysed now by [work] */
	suspend fun analyse(key: Key, work: suspend () -> R?): R? {
		val (deferred, owner) = synchronized(this) {
			get(key)?.let { return it }
			inFlight[key]?.let { it to false } ?: CompletableDeferred<R?>().also { inFlight[key] = it }.let { it to true }
		}
		if (!owner) return deferred.await()
		val result = try {
			work()
		} catch (e: CancellationException) {
			synchronized(this) { inFlight.remove(key) }
			deferred.complete(null)
			throw e
		} catch (e: Exception) {
			null
		}
		synchronized(this) {
			inFlight.remove(key)
			if (result != null) put(key, result)
		}
		deferred.complete(result)
		return result
	}

	@Synchronized
	fun size(): Int = entries.size

	/** review run 25: an analysis is running now (yt-dlp must not be replaced under it) */
	@Synchronized fun busy(): Boolean = inFlight.isNotEmpty()
}
