package org.websnake.vidchain.fallback.ledger

import org.websnake.vidchain.fallback.classifier.UserIntent

/** Ledger for JVM tests and as the fallback when the database cannot be opened. All methods are synchronized. */
class InMemoryLedger : AttemptLedger {
	private val intents = HashMap<String, Pair<UserIntent, Long>>()
	private val handled = HashSet<Pair<String, String>>()
	private val attempts = HashMap<Pair<String, Int>, AttemptRow>()
	private val children = HashMap<String, String>()

	@Synchronized override fun recordIntent(downloadId: String, intent: UserIntent, timeMs: Long): Long {
		val seq = (intents[downloadId]?.second ?: 0L) + 1
		intents[downloadId] = intent to seq
		return seq
	}

	@Synchronized override fun intentOf(downloadId: String) = intents[downloadId]?.first ?: UserIntent.NONE
	@Synchronized override fun intentSeq(downloadId: String) = intents[downloadId]?.second ?: 0L
	@Synchronized override fun markHandled(downloadId: String, signature: String, timeMs: Long) = handled.add(downloadId to signature)

	@Synchronized override fun claim(parentId: String, attemptNo: Int, method: String, timeMs: Long): Boolean {
		val key = parentId to attemptNo
		if (key in attempts) return false
		attempts[key] = AttemptRow(parentId, attemptNo, method, AttemptState.RUNNING, startedMs = timeMs)
		return true
	}

	@Synchronized override fun update(parentId: String, attemptNo: Int, state: AttemptState, childId: String?, reason: String?, timeMs: Long) {
		val key = parentId to attemptNo
		val row = attempts[key] ?: return
		attempts[key] = row.copy(state = state, childId = childId ?: row.childId, reason = reason, endedMs = timeMs)
	}

	@Synchronized override fun attempts(parentId: String) =
		attempts.values.filter { it.parentId == parentId }.sortedBy { it.attemptNo }

	@Synchronized override fun mapChild(childId: String, parentId: String) { children[childId] = parentId }
	@Synchronized override fun parentOf(childId: String) = children[childId]
}
