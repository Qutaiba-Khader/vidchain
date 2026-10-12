package org.websnake.vidchain.web.refetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.web.refetch.RefetchPolicy.Verdict
import java.io.File

class RefetchPolicyTest {
	private fun rules() = DisguiseRules(File("src/main/assets/vidchain-disguise-rules.json").readText())
	private fun armed(budget: Int = 40) = RefetchPolicy.PageGate(budget, armed = true)
	private fun facts(url: String, method: String = "GET", main: Boolean = false, headers: Map<String, String> = mapOf("Accept" to "*/*")) =
		RefetchPolicy.Facts(url, method, main, headers)
	private fun decide(url: String, gate: RefetchPolicy.PageGate = armed(), rules: DisguiseRules = rules(), never: RefetchPolicy.NeverList = RefetchPolicy.NeverList(),
		headers: Map<String, String> = mapOf("Accept" to "*/*"), method: String = "GET", main: Boolean = false) =
		RefetchPolicy.decide(facts(url, method, main, headers), gate, rules, never)

	@Test fun possibleMediaRequestsAreFetched() {
		assertEquals(Verdict.Fetch(null), decide("https://api.example.com/v1/video/123/sources"))
		assertEquals(Verdict.Fetch(null), decide("https://cdn.example.com/stream/abc"))
		assertEquals(Verdict.Fetch("playlist-folder"), decide("https://cdn.example.com/hls/123/abc.jpg"))
		assertEquals(Verdict.Fetch("playlist-file-name"), decide("https://cdn.example.com/v/master.txt"))
		assertEquals(Verdict.Fetch("playlist-word-in-query"), decide("https://cdn.example.com/f.txt?file=a.m3u8"))
		assertEquals(Verdict.Fetch(null), decide("https://cdn.example.com/get.php?type=m3u8"))
		assertEquals(Verdict.Fetch(null), decide("https://cdn.example.com/api/video.json"))
	}

	@Test fun whatIsNeverFetched() {
		for ((url, why) in listOf(
			"https://cdn.example.com/a/logo.png" to "static",
			"https://cdn.example.com/app.js" to "static",
			"https://cdn.example.com/v/clip.mp4" to "media address",
			"https://cdn.example.com/v/index.m3u8" to "media address",
			"https://cdn.example.com/v/seg-001.ts" to "media address",
			"https://rr1---sn-x.googlevideo.com/videoplayback?itag=18" to "media address",
			"https://fonts.googleapis.com/css2?family=x" to "fonts / analytics",
			"https://cdn.example.com/stream/abc?token=xyz" to "one-time link",
			"https://bucket.s3.amazonaws.com/v?X-Amz-Signature=1&X-Amz-Expires=3" to "one-time link",
			"https://cdn.example.com/v?e=1700000000&st=abc" to "one-time link",
			"wss://chat.example.com/socket" to "scheme",
			"data:text/plain,x" to "scheme",
		)) assertEquals(url, Verdict.Skip(why), decide(url))
		assertEquals(Verdict.Skip("main frame"), decide("https://example.com/watch", main = true))
		assertEquals(Verdict.Skip("not GET"), decide("https://example.com/api", method = "POST"))
		assertEquals(Verdict.Skip("range / upgrade"), decide("https://example.com/api", headers = mapOf("Range" to "bytes=0-")))
		assertEquals(Verdict.Skip("accept"), decide("https://example.com/api", headers = mapOf("Accept" to "image/webp,*/*")))
		assertEquals(Verdict.Skip("accept"), decide("https://example.com/api", headers = mapOf("accept" to "video/webm,*/*")))
	}

	@Test fun theGateArmsLimitsAndStopsAfterThreeHandBacks() {
		val dormant = RefetchPolicy.PageGate(40, armed = false)
		assertEquals(Verdict.Skip("page budget"), decide("https://example.com/api", dormant))
		dormant.armed = true
		assertEquals(Verdict.Fetch(null), decide("https://example.com/api", dormant))
		// deciding takes nothing: only a request really made does (T8.12 review, a skipped request used the budget)
		val small = armed(2)
		repeat(5) { assertEquals(Verdict.Fetch(null), decide("https://example.com/a$it", small)) }
		assertTrue(small.take()); assertTrue(small.take()); assertFalse(small.take())
		assertEquals(Verdict.Skip("page budget"), decide("https://example.com/c", small))
		assertEquals("re-fetched=2 handed-back=0 found=0", small.summary())
		val g = armed()
		repeat(3) { g.handedBack() }
		assertTrue(g.stopped())
		assertEquals(Verdict.Skip("page budget"), decide("https://example.com/a", g))
	}

	@Test fun documentsLongPollsAndMoreSignedLinksAreLeftToTheWebView() {
		assertEquals(Verdict.Skip("document"), decide("https://embed.example/player/1", headers = mapOf("Accept" to "text/html,application/xhtml+xml,*/*;q=0.8")))
		for (u in listOf("https://chat.example/socket.io/?EIO=4&transport=polling&t=abc", "https://x.example/poll?sid=1&transport=polling"))
			assertEquals(u, Verdict.Skip("long poll"), decide(u))
		for (u in listOf("https://storage.googleapis.com/b/o?X-Goog-Signature=1", "https://v.example/a?wmsAuthSign=1", "https://v.example/a?k=1",
				"https://scontent.example/v?oh=1&oe=2", "https://v.example/a?auth_key=1", "https://r.example/videoplayback?expire=1&lsig=2"))
			assertEquals(u, Verdict.Skip("one-time link"), decide(u))
	}

	@Test fun anAddressHandedBackIsNotTriedAgainNorItsSiblings() {
		val never = RefetchPolicy.NeverList()
		never.add("https://cdn.example.com/api/123/info")
		assertEquals(Verdict.Skip("handed back before"), decide("https://cdn.example.com/api/456/info", never = never))
		assertEquals(Verdict.Fetch(null), decide("https://cdn.example.com/api/456/other", never = never))
	}

	@Test fun sniffing() {
		assertEquals("body-hls", RefetchPolicy.sniff("image/jpeg", "\uFEFF #EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
		assertEquals("body-dash", RefetchPolicy.sniff("text/plain", "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash\">".toByteArray()))
		assertEquals("ct", RefetchPolicy.sniff("application/octet-stream", byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
		assertEquals("ct-hls", RefetchPolicy.sniff("application/vnd.apple.mpegurl; charset=utf-8", "x".toByteArray()))
		assertEquals("ct-dash", RefetchPolicy.sniff("application/dash+xml", ByteArray(0)))
		assertEquals("ct", RefetchPolicy.sniff("video/mp4", ByteArray(0)))
		assertNull(RefetchPolicy.sniff("video/mp2t", ByteArray(0)))
		assertNull(RefetchPolicy.sniff("application/json", "{\"a\":1}".toByteArray()))
		assertNull(RefetchPolicy.sniff(null, "<html><body>MPD</body>".toByteArray()))
		assertTrue(RefetchPolicy.challenge("text/html; charset=utf-8", "<html><div id=\"cf-chl-widget\">".toByteArray()))
		assertTrue(RefetchPolicy.challenge("text/html", "<html><head><title>Just a moment...</title>".toByteArray()))
		assertFalse(RefetchPolicy.challenge("text/html", "#EXTM3U".toByteArray()))
		// a page that merely uses a captcha, or data about one, is not a bot check (T8.12 review)
		assertFalse(RefetchPolicy.challenge("text/html", "<script src=\"https://www.google.com/recaptcha/api.js\"></script>".toByteArray()))
		assertFalse(RefetchPolicy.challenge("application/json", "{\"captcha_required\":false,\"cf-chl\":1}".toByteArray()))
	}
}
