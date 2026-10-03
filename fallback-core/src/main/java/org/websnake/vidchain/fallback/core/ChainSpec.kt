package org.websnake.vidchain.fallback.core

/**
 * The fallback chains (PLAN.md "Architecture"): what runs, in order, AFTER the current method failed.
 * Method ids: C player_client retries, S session context, Y yt-dlp any URL, P NewPipe streams, F ffmpeg, N nightly yt-dlp,
 * W WebView catcher, R redirect unwrap, L file-host resolvers, H page scraping, G gallery-dl, U you-get, T Streamlink,
 * X lux, O plain GET, A aria2c, D DownloadManager, M Media3. Methods that are not registered yet are skipped.
 */
object ChainSpec {
	const val STUB = "STUB"

	val chains: Map<String, List<String>> = mapOf(
		"B1" to listOf("C", "S", "Y", "P", "F", "N", "W"),
		"B2" to listOf("R", "S", "Y", "N", "H", "W", "G", "U", "T", "X"),
		"B3" to listOf("R", "L", "S", "Y", "P", "N", "H", "W", "G", "U", "T", "X"),
		"B4" to listOf("R", "L", "S", "O", "A", "D"),
		"B5" to listOf("S", "Y", "F", "M", "N", "T"),
		"B6" to listOf("A"),
	)

	/** the chain for a URL class; [includeStub] puts the always-failing stub first (P1 test path, removed when real methods exist) */
	fun stepsFor(cls: UrlClass, includeStub: Boolean): List<String> =
		(if (includeStub) listOf(STUB) else emptyList()) + chains.getValue(cls.chain)

	/**
	 * Pure: the next method to try, given the chain, the methods already attempted for this parent, and what is available
	 * (registered and switched on). null = the chain is exhausted.
	 */
	fun nextMethod(steps: List<String>, attempted: Collection<String>, available: (String) -> Boolean): String? =
		steps.firstOrNull { it !in attempted && available(it) }
}
