package org.websnake.vidchain.ytdlp.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoTokensTest {
	private val t = "Mn" + "a".repeat(60) + "_-x=="

	@Test fun plainBase64IsAccepted() {
		assertNotNull(PoTokens.of("CgtWaXNpdG9yRGF0YSiP%3D", t, t))
		assertFalse(PoTokens.of("CgtWaXNpdG9yRGF0YSiP%3D", t, t).toString().contains(t))   // never printed
	}

	@Test fun anythingThatCouldSplitTheExtractorArgumentIsRefused() {
		for (bad in listOf("$t;player_client=x", "$t,web.gvs+x", "$t+y", "a:b$t", "$t\n", "short", "")) {
			assertNull(bad, PoTokens.of("CgtWaXNpdG9y", bad, t))
			assertNull(bad, PoTokens.of("CgtWaXNpdG9y", t, bad))
		}
		assertNull(PoTokens.of("Cgt;visitor", t, t))
		assertNull(PoTokens.of(null, t, t))
	}

	@Test fun videoIds() {
		val id = "dQw4w9WgXcQ"
		for (u in listOf("https://www.youtube.com/watch?v=$id", "https://m.youtube.com/watch?feature=share&v=$id&t=3", "https://youtu.be/$id?si=abc",
				"https://www.youtube.com/shorts/$id", "https://youtube.com/live/$id", "https://www.youtube-nocookie.com/embed/$id", "https://music.youtube.com/watch?v=$id"))
			assertEquals(u, id, PoTokens.videoId(u))
		for (u in listOf("https://www.youtube.com/watch?v=x", "https://www.youtube.com/@channel", "https://evil.example/shorts/$id", "https://youtu.be/", "not a url"))
			assertNull(u, PoTokens.videoId(u))
	}
}
