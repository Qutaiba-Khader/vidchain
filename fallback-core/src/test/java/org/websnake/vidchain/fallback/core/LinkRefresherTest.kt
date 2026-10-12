package org.websnake.vidchain.fallback.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.Engine

class LinkRefresherTest {
	private fun d(expected: Long?) = HostDownload("1", "https://site.example/watch/1", "https://cdn.example/v/a.mp4?sig=OLD",
		DownloadSnapshot(Engine.REGULAR, DownloadSnapshot.CLOSE, isFileUrlExpired = true), bytes = 500, referer = "https://site.example/watch/1",
		expectedBytes = expected)

	@Test fun theSameSizeProvesTheSameFile() = runBlocking {
		val sizes = mapOf("https://cdn.example/v/a.mp4?sig=NEW" to 9_000L, "https://cdn2.example/other.mp4" to 9_000L, "https://cdn.example/v/b.mp4" to 7_000L)
		val r = LinkRefresher(listOf({ _ -> listOf("https://cdn.example/v/b.mp4", "https://cdn.example/v/a.mp4?sig=NEW", "https://cdn2.example/other.mp4") }), { u, _ -> sizes[u] })
		assertEquals("https://cdn.example/v/a.mp4?sig=NEW", r.fresh(d(9_000)))       // same place is tried first
	}

	@Test fun aDifferentSizeIsNeverSplicedIn() = runBlocking {
		val r = LinkRefresher(listOf({ _ -> listOf("https://cdn.example/v/a.mp4?sig=NEW") }), { _, _ -> 8_999L })
		assertNull(r.fresh(d(9_000)))
	}

	@Test fun withoutAKnownSizeOnlyTheSameHostAndPathCounts() = runBlocking {
		val r = LinkRefresher(listOf({ _ -> listOf("https://other.example/x.mp4", "https://cdn.example/v/a.mp4?sig=NEW") }), { _, _ -> 9_000L })
		assertEquals("https://cdn.example/v/a.mp4?sig=NEW", r.fresh(d(null)))
		val none = LinkRefresher(listOf({ _ -> listOf("https://other.example/x.mp4") }), { _, _ -> 9_000L })
		assertNull(none.fresh(d(null)))
	}

	@Test fun theSecondSourceIsAskedWhenTheFirstHasNothing() = runBlocking {
		val r = LinkRefresher(listOf({ _ -> null }, { _ -> listOf("https://cdn.example/v/a.mp4?sig=W") }), { _, _ -> 9_000L })
		assertEquals("https://cdn.example/v/a.mp4?sig=W", r.fresh(d(9_000)))
	}
}
