package org.websnake.vidchain.engine

/**
 * Engines that keep crashing are benched: per (method, engine hash, ABI), [threshold] crashes within [windowMs]
 * quarantine that combination for [banMs]. A new engine version (new hash) starts clean. Only real crashes count:
 * OS kills, timeouts, cancels and ordinary failures never do; a success clears the record.
 */
class Quarantine(
	private val store: KeyValueStore,
	private val clock: () -> Long = System::currentTimeMillis,
	private val threshold: Int = 3,
	private val windowMs: Long = 24 * 3_600_000L,
	private val banMs: Long = 24 * 3_600_000L,
) {
	private fun key(method: String, engineHash: String, abi: String) = "quarantine.$method.$engineHash.$abi"

	@Synchronized
	fun isQuarantined(method: String, engineHash: String, abi: String): Boolean {
		val until = store.get(key(method, engineHash, abi) + ".until")?.toLongOrNull() ?: return false
		return clock() < until
	}

	@Synchronized
	fun record(method: String, engineHash: String, abi: String, outcome: EngineOutcome) {
		val k = key(method, engineHash, abi)
		when (outcome) {
			EngineOutcome.Success -> { store.put("$k.crashes", null); store.put("$k.until", null) }
			is EngineOutcome.Crashed -> {
				val now = clock()
				val recent = store.get("$k.crashes").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < windowMs } + now
				store.put("$k.crashes", recent.joinToString(","))
				if (recent.size >= threshold) store.put("$k.until", (now + banMs).toString())
			}
			else -> Unit
		}
	}
}
