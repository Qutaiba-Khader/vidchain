package org.websnake.vidchain.fallback.core

/** What a fallback attempt knows about the download it rescues (no secrets: cookies stay in the app's stores). */
data class FallbackContext(
	val parentId: String,
	val url: String,                    // the source URL (the page / watch URL when the download came from an extractor)
	val mediaUrl: String,               // the URL the current method was downloading
	val urlClass: UrlClass,
	val referer: String? = null,
	val userAgent: String? = null,
	val fileName: String? = null,
	val failureClass: String? = null,
	val attemptNo: Int = 0,
	val destPath: String? = null,        // the parent's destination file (executors write next to it)
	val expectMedia: Boolean = true,
	val preferredHeight: Int? = null,      // the resolution the user picked for the current method (yt-dlp format choice)
	val audioOnly: Boolean = false,
)

/** A resolved media source for the existing downloaders (resolver output) - headers never go into traces. */
data class Candidate(
	val url: String,
	val headers: Map<String, String> = emptyMap(),
	val fileName: String? = null,
	val kind: String = "file",          // file | hls | dash | magnet
)

sealed class MethodOutcome {
	/** hand this source to the existing download queue as a child download */
	data class Resolved(val candidate: Candidate) : MethodOutcome()
	/**
	 * The method itself produced the file (executors): [path] is a temp file the method owns. The coordinator verifies it,
	 * commits it next to the parent's destination on PASS / UNSURE (never overwriting) and deletes it on FAIL.
	 */
	data class Delivered(val path: String, val extras: List<String> = emptyList()) : MethodOutcome()   // extras: the other files of a multi-file torrent
	/** [retryable]: the system stopped the method (OS kill), not the method failing: the coordinator runs it once more */
	data class Failed(val reason: String, val retryable: Boolean = false) : MethodOutcome()
	/** the download really lives at [url] (redirect, short link, wrapper): the chain continues there, in that URL's class */
	data class Redirected(val url: String, val note: String = "") : MethodOutcome()
	data class Unsupported(val reason: String) : MethodOutcome()
}

/** One fallback method. RESOLVERs return a Candidate, EXECUTORs deliver a file. */
interface FallbackMethod {
	val id: String
	val kind: Kind
	enum class Kind { RESOLVER, EXECUTOR }
	suspend fun attempt(ctx: FallbackContext): MethodOutcome
}

/** P1 test path: proves a failure reaches the chain and the chain moves on. Never resolves anything. */
object StubAlwaysFailMethod : FallbackMethod {
	override val id = ChainSpec.STUB
	override val kind = FallbackMethod.Kind.RESOLVER
	override suspend fun attempt(ctx: FallbackContext): MethodOutcome = MethodOutcome.Failed("stub method: always fails by design")
}
