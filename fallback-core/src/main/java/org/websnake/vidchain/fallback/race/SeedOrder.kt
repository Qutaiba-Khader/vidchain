package org.websnake.vidchain.fallback.race

/**
 * T9 decision 1, stage 4c: the order the APK ships per known site, built from the site x method test matrix on the
 * phone (tools/phone/matrix.py). Until a site has its own results on the phone, its row here decides; sites not
 * listed use the generic row ([Scorecard.GENERIC]), and without that the chain's own order.
 */
object SeedOrder {
	val SEEDS: Map<String, List<String>> = emptyMap()
}
