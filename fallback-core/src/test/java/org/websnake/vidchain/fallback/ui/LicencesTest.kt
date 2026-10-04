package org.websnake.vidchain.fallback.ui

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The licences screen lists the original app, every native / Python component and every library of the asset. */
class LicencesTest {
	@Test fun theShippedAssetRendersEveryComponent() {
		val doc = JSONObject(File("src/main/assets/${LicencesText.ASSET}").readText())
		val text = LicencesText.render(doc)
		assertTrue(text.startsWith("VidChain is based on AIO Video Downloader by shibaFoss"))
		for (k in listOf("native_and_python", "libraries")) {
			val a = doc.getJSONArray(k)
			assertTrue(k, a.length() > 20)
			for (i in 0 until a.length()) {
				val o = a.getJSONObject(i)
				assertTrue(o.toString(), text.contains(o.optString("name").ifEmpty { o.getString("id") }))
			}
		}
		for (must in listOf("liblux.so", "FFmpeg", "aria2", "CPython", "yt-dlp", "gallery-dl", "streamlink", "com.squareup.okhttp3:okhttp"))
			assertTrue(must, text.contains(must))
	}
}
