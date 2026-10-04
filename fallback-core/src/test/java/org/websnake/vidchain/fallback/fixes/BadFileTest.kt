package org.websnake.vidchain.fallback.fixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BadFileTest {
	private val html = "<!DOCTYPE html><html><head><title>403</title></head><body>Forbidden</body></html>".toByteArray()
	private val mp4 = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
		'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 2, 0)

	@Test fun htmlSavedAsVideoIsBad() {
		assertEquals("not media: html", BadFile.reason("fake.mp4", html, html.size.toLong(), html.size.toLong()))
	}

	@Test fun emptyAndShortFilesAreBad() {
		assertEquals("empty file", BadFile.reason("clip.mp4", ByteArray(0), 0, null))
		assertNotNull(BadFile.reason("clip.mp4", mp4, 36, 429_760))
	}

	@Test fun realMediaAndOtherFilesPass() {
		assertNull(BadFile.reason("clip.mp4", mp4, 429_760, 429_760))
		assertNull(BadFile.reason("clip.mp4", mp4, 429_760, null))                 // unknown size
		assertNull(BadFile.reason("page.html", html, html.size.toLong(), null))     // not named as media
		assertNull(BadFile.reason("archive.zip", html, html.size.toLong(), null))
		assertNull(BadFile.reason("odd.mp4", ByteArray(64) { 7 }, 64, null))        // unknown bytes: not clear evidence
	}
}
