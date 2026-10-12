package org.websnake.vidchain.web.refetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DisguiseRulesTest {
	private fun rules() = DisguiseRules(File("src/main/assets/vidchain-disguise-rules.json").readText())

	@Test fun genericRulesNameDisguisedPlaylistsButNotOrdinaryFiles() {
		val r = rules()
		for (u in listOf("https://c.example/hls/1080/seg.jpg", "https://c.example/live_abc/x.png", "https://c.example/p/master.css",
				"https://c.example/x/chunklist_w1.txt", "https://c.example/f.html?file=a.m3u8", "https://c.example/f.txt?hls=1"))
			assertEquals(u, true, r.match(u) != null)
		for (u in listOf("https://c.example/img/logo.png", "https://c.example/css/site.css", "https://c.example/index.html",
				"https://c.example/robots.txt", "https://c.example/api/data", "https://c.example/v/master.m3u8"))
			assertNull(u, r.match(u))
	}

	@Test fun learnedRulesCoverSiblingsAndExpire() {
		val r = rules()
		assertNull(r.match("https://c.example/v/42/a1.png", now = 0))
		r.learn("https://c.example/v/41/a0.png", now = 0)
		assertEquals("learned", r.match("https://c.example/v/42/a1.png", now = 1_000))
		assertNull(r.match("https://other.example/v/42/a1.png", now = 1_000))
		assertNull(r.match("https://c.example/v/42/a1.png", now = DisguiseRules.LEARNED_TTL_MS + 1))
	}

	@Test fun aRuleThatKeepsMissingIsDroppedUntilEvidenceSaysOtherwise() {
		val r = rules()
		val u = "https://c.example/hls/1/seg.jpg"
		repeat(DisguiseRules.DEMOTE_AFTER) { r.miss("https://c.example/hls/$it/seg.jpg") }
		assertNull(r.match(u))
		r.learn(u)
		assertEquals("learned", r.match(u))
	}

	@Test fun shapes() {
		assertEquals("c.example/hls/<n>/<id>/*.jpg", DisguiseRules.shape("https://C.example/hls/1080/ab12cd34ef/seg-9.jpg?x=1"))
		assertEquals("c.example/*.txt", DisguiseRules.shape("https://c.example/a.txt"))
		assertNull(DisguiseRules.shape("https://c.example/"))
	}
}
