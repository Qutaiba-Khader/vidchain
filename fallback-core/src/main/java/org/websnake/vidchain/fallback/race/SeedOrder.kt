package org.websnake.vidchain.fallback.race

/**
 * T9 decision 1, stage 4c: the order the APK ships per known site, built from the site x method test matrix on the
 * phone (tools/phone/matrix.py, A50 / Android 11, 2026-10-14: 12 links x 18 methods, one method at a time).
 * Until a site has its own results on the phone, its row here decides; sites not listed use the generic row
 * ([Scorecard.GENERIC]), and without that the chain's own order.
 *
 * Each row: the methods that delivered on that site, fastest first, then the rest in the chain's order. Sites where
 * no method delivered on the test network (tiktok.com, vimeo.com, ted.com) are not listed: they keep the chain's
 * order. facebook.com: the matrix's first link was a removed video; the row comes from the owner's reel (2026-10-14).
 * There is deliberately no generic ("*") row: the matrix's generic link was a direct file, which says nothing about
 * the order for an unknown page.
 */
object SeedOrder {
	private val YOUTUBE = listOf("Y", "T", "X", "N", "C", "S", "P", "F", "W", "R", "L", "H", "G", "U", "O", "A", "D", "M")
	private val FACEBOOK = listOf("N", "Y", "W", "H", "C", "S", "P", "F", "R", "L", "G", "U", "T", "X", "O", "A", "D", "M")
	private val X = listOf("H", "U", "W", "G", "N", "Y", "C", "S", "P", "F", "R", "L", "T", "X", "O", "A", "D", "M")

	val SEEDS: Map<String, List<String>> = mapOf(
		"youtube.com" to YOUTUBE,          // Y 9.5 s, T 10.3 s, X 11.1 s, N 33.5 s, C 106.9 s
		"youtu.be" to YOUTUBE,
		"x.com" to X,                      // H 1.2 s, U 3.7 s, W 3.9 s, G 8.5 s, N 11.6 s, Y 11.7 s
		"twitter.com" to X,
		"instagram.com" to listOf("H", "N", "Y", "W", "C", "S", "P", "F", "R", "L", "G", "U", "T", "X", "O", "A", "D", "M"),   // H 0.7, N 8.1, Y 8.2, W 25.5 s
		"reddit.com" to listOf("W", "C", "S", "Y", "P", "F", "N", "R", "L", "H", "G", "U", "T", "X", "O", "A", "D", "M"),      // W 7.7 s
		"dailymotion.com" to listOf("T", "N", "W", "Y", "C", "S", "P", "F", "R", "L", "H", "G", "U", "X", "O", "A", "D", "M"), // T 19.4, N 47.3, W 48.2, Y 73.2 s
		"soundcloud.com" to listOf("N", "Y", "C", "S", "P", "F", "W", "R", "L", "H", "G", "U", "T", "X", "O", "A", "D", "M"),  // N 15.7, Y 17.2 s
		"facebook.com" to FACEBOOK,        // N 16.9 s, Y 18.1 s, W 25.6 s (the owner's reel; H fixed after this run)
		"fb.watch" to FACEBOOK,
		"web.facebook.com" to FACEBOOK,
		"fb.com" to FACEBOOK,
		"fb.gg" to FACEBOOK,
		"pinterest.com" to listOf("G", "Y", "X", "N", "W", "C", "S", "P", "F", "R", "L", "H", "U", "T", "O", "A", "D", "M"),   // G 9.0, Y 11.8, X 19.3, N 27.2, W 39.3 s
	)
}
