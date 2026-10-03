package org.websnake.vidchain.fallback.ledger

import org.websnake.vidchain.fallback.classifier.UserIntent

/** Where one fallback attempt stands. */
enum class AttemptState { RUNNING, CHILD, DELIVERED, UNSURE, FAILED, UNSUPPORTED }

data class AttemptRow(
	val parentId: String,
	val attemptNo: Int,
	val method: String,
	val state: AttemptState,
	val childId: String? = null,
	val reason: String? = null,
	val startedMs: Long = 0,
	val endedMs: Long? = null,
)

/**
 * The fallback system's own record (never the upstream database): user intents recorded at the UI seams, which failures
 * were already handled, every attempt per parent download, and which child download belongs to which parent.
 * Implementations must make [claim] and [markHandled] atomic: two callers racing for the same key, exactly one wins.
 */
interface AttemptLedger {
	/** Record what the user asked for. Returns the new intent sequence number of that download (starts at 1). */
	fun recordIntent(downloadId: String, intent: UserIntent, timeMs: Long): Long
	fun intentOf(downloadId: String): UserIntent
	fun intentSeq(downloadId: String): Long

	/** true the first time this (download, signature) pair is seen; false afterwards */
	fun markHandled(downloadId: String, signature: String, timeMs: Long): Boolean

	/** Atomically claim attempt [attemptNo] of [parentId] (state RUNNING). false = somebody else already holds it. */
	fun claim(parentId: String, attemptNo: Int, method: String, timeMs: Long): Boolean
	fun update(parentId: String, attemptNo: Int, state: AttemptState, childId: String?, reason: String?, timeMs: Long)
	/** attempts of one parent, in attempt order */
	fun attempts(parentId: String): List<AttemptRow>

	fun mapChild(childId: String, parentId: String)
	fun parentOf(childId: String): String?
}
