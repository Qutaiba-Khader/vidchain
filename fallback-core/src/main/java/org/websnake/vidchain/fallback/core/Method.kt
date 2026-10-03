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
	/** the method itself produced the file (executors) */
	data class Delivered(val path: String) : MethodOutcome()
	data class Failed(val reason: String) : MethodOutcome()
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
