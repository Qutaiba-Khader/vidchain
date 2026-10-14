package org.websnake.vidchain.ytdlp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9 decision 2: the analysis JSON kept for --load-info-json never holds a cookie */
class YtDlpReplayTest {
	private val json = """{"id":"1","title":"t","extractor":"twitter","cookies":"auth_token=SECRET",
		"http_headers":{"User-Agent":"UA","Cookie":"a=SECRET","Authorization":"Bearer SECRET"},
		"formats":[{"format_id":"hd","url":"https://video.twimg.com/v.mp4","cookies":"ct0=SECRET",
		  "http_headers":{"User-Agent":"UA","cookie":"b=SECRET","Referer":"https://x.com/"}}]}"""

	@Test fun cookiesAndAuthHeadersAreRemoved() {
		val out = YtDlpInfo.stripSecrets(json)
		assertFalse(out.contains("SECRET"))
		val o = JSONObject(out)
		assertEquals("UA", o.getJSONObject("http_headers").getString("User-Agent"))
		val f = o.getJSONArray("formats").getJSONObject(0)
		assertEquals("https://x.com/", f.getJSONObject("http_headers").getString("Referer"))
		assertEquals("https://video.twimg.com/v.mp4", f.getString("url"))
	}

	@Test fun aPlaylistAnswerBecomesItsFirstEntry() {
		val pl = """{"_type":"playlist","entries":[{"id":"a","url":"https://cdn/a.mp4","cookies":"x=SECRET"},{"id":"b"}]}"""
		val out = JSONObject(YtDlpInfo.stripSecrets(pl))
		assertEquals("a", out.getString("id"))
		assertFalse(out.has("cookies"))
	}

	@Test fun proofOfOriginAddressesAreNotReplayed() {
		assertTrue(YtDlpInfo.replayable(json))
		val pot = """{"formats":[{"format_id":"18","url":"https://rr1.googlevideo.com/videoplayback?itag=18&pot=MnQ"}]}"""
		assertFalse(YtDlpInfo.replayable(pot))
		assertFalse(YtDlpInfo.replayable("""{"formats":[]}"""))
		assertTrue(YtDlpInfo.replayable("""{"url":"https://cdn/x.mp4"}"""))
	}

	@Test fun aSingleFileOfTheTopHeightIsPreferredToAMerge() {
		val x = YtDlpInfo.parse("""{"formats":[
			{"format_id":"hls-audio","protocol":"m3u8_native","vcodec":"none"},
			{"format_id":"http-800","protocol":"https","height":328,"tbr":800},
			{"format_id":"http-12128","protocol":"https","height":560,"tbr":12128},
			{"format_id":"hls-2139","protocol":"m3u8_native","vcodec":"avc1","acodec":"none","height":560}]}""")
		assertEquals("http-12128", YtDlpFormats.singleFileOfTopHeight(x))
		// a higher video-only stream exists: no single file of the top height, keep the merge
		val ig = YtDlpInfo.parse("""{"formats":[
			{"format_id":"v1080","protocol":"https","vcodec":"avc1","acodec":"none","height":1080},
			{"format_id":"a","protocol":"https","vcodec":"none","acodec":"mp4a"},
			{"format_id":"c720","protocol":"https","height":720}]}""")
		assertEquals(null, YtDlpFormats.singleFileOfTopHeight(ig))
	}
}
