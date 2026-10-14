package org.websnake.vidchain.fallback.core

/**
 * T9 decision 1 (owner, 2026-10-14): the original app's own ways to fetch a video are methods in the one Methods list,
 * with their own switches, results and place in the learned order.
 *
 * They are not run by the chain: each is the app's own download engine for one kind of download, and it runs as the
 * download row itself. Switched off, the app does not start that engine and VidChain's methods take the download at
 * once; while it runs, it is given its site's usual time to prove its first bytes before the other methods join in
 * (the hedged start, [FallbackCoordinator]).
 */
object OwnPath {
	/** the app's yt-dlp engine for a link it analysed (the picker's formats) */
	const val YTDLP = "OY"
	/** the app's social path: X, Instagram, Facebook, TikTok links ("High Quality") */
	const val SOCIAL = "OS"
	/** the app's own HTTP downloader for a file address */
	const val DIRECT = "OD"
	/** a video the in-app browser detected on a page, downloaded by the app */
	const val BROWSER = "OB"

	val ALL = listOf(YTDLP, SOCIAL, DIRECT, BROWSER)

	/** the usual time to the first bytes before the site has its own results (yt-dlp starts a process first) */
	fun defaultProofMs(id: String): Long = if (id == YTDLP || id == SOCIAL) 12_000L else 5_000L

	/** the own path of a download: [viaExtractor] = yt-dlp format chosen, [social] = a social link, [fromBrowser] = browser find */
	fun of(viaExtractor: Boolean, social: Boolean, fromBrowser: Boolean): String = when {
		viaExtractor && social -> SOCIAL
		viaExtractor -> YTDLP
		fromBrowser -> BROWSER
		else -> DIRECT
	}
}
