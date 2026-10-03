package org.websnake.vidchain.fallback.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.Engine
import org.websnake.vidchain.fallback.classifier.StatusKey
import org.websnake.vidchain.fallback.ledger.InMemoryLedger

/**
 * Wave 1a contract (T2.6): with the methods that exist (C, S, Y, R, O), every chain falls through in exactly the
 * order ChainSpec gives, methods not built yet are skipped, and nothing runs twice.
 */
class ChainContractTest {
	private class Fail(override val id: String, override val kind: FallbackMethod.Kind, val log: MutableList<String>) : FallbackMethod {
		override suspend fun attempt(ctx: FallbackContext): MethodOutcome { log += id; return MethodOutcome.Failed("no") }
	}

	private class Host(val url: String) : FallbackHost {
		var snap = DownloadSnapshot(Engine.REGULAR, DownloadSnapshot.DOWNLOADING, isRunning = true)
		override fun downloads() = listOf(HostDownload("1", url, url, snap, filePath = "/tmp/vidchain-contract/x.mp4"))
		override fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String? = null
	}

	private fun order(url: String, ids: List<String> = WAVE_1A): List<String> = runBlocking {
		val log = ArrayList<String>()
		val methods = ids.map { Fail(it, if (it == "R") FallbackMethod.Kind.RESOLVER else FallbackMethod.Kind.EXECUTOR, log) }
		val host = Host(url)
		val c = FallbackCoordinator(host, InMemoryLedger(), methods, this, FallbackCoordinator.Config(includeStub = false))
		c.tick()
		host.snap = DownloadSnapshot(Engine.REGULAR, DownloadSnapshot.CLOSE, statusKey = StatusKey.DOWNLOAD_FAILED)
		c.tick(); c.drain()
		log
	}

	@Test fun b1YouTube() = assertEquals(listOf("C", "S", "Y"), order("https://www.youtube.com/watch?v=abc"))
	@Test fun b2ListedSite() = assertEquals(listOf("R", "S", "Y"), order("https://vimeo.com/123"))
	@Test fun b3UnlistedPage() = assertEquals(listOf("R", "S", "Y"), order("https://some-blog.example/post/1"))
	@Test fun b4DirectFile() = assertEquals(listOf("R", "S", "O"), order("https://cdn.example.org/v/clip.mp4"))
	@Test fun b5HlsDash() = assertEquals(listOf("S", "Y"), order("https://cdn.example.org/live/master.m3u8"))
	@Test fun b6TorrentHasNothingYet() = assertEquals(emptyList<String>(), order("magnet:?xt=urn:btih:abc"))

	// wave 1b (T3.8): L, H, W, F, P, M, D join; N, G, U, T, X and A are not built yet
	@Test fun wave1bB1YouTube() = assertEquals(listOf("C", "S", "Y", "P", "F", "W"), order("https://www.youtube.com/watch?v=abc", WAVE_1B))
	@Test fun wave1bB2ListedSite() = assertEquals(listOf("R", "S", "Y", "H", "W"), order("https://vimeo.com/123", WAVE_1B))
	@Test fun wave1bB3UnlistedPage() = assertEquals(listOf("R", "L", "S", "Y", "P", "H", "W"), order("https://some-blog.example/post/1", WAVE_1B))
	@Test fun wave1bB4DirectFile() = assertEquals(listOf("R", "L", "S", "O", "D"), order("https://cdn.example.org/v/clip.mp4", WAVE_1B))
	@Test fun wave1bB5HlsDash() = assertEquals(listOf("S", "Y", "F", "M"), order("https://cdn.example.org/live/master.m3u8", WAVE_1B))
	@Test fun wave1bB6Torrent() = assertEquals(emptyList<String>(), order("magnet:?xt=urn:btih:abc", WAVE_1B))

	@Test fun everyChainIsAPrefixFreeOrderOfKnownMethods() {
		val known = setOf("C", "S", "Y", "P", "F", "N", "W", "R", "H", "G", "U", "T", "X", "L", "O", "A", "D", "M")
		for ((chain, steps) in ChainSpec.chains) {
			assertEquals(chain, steps.size, steps.toSet().size)
			assertEquals(chain, emptySet<String>(), steps.toSet() - known)
		}
	}

	companion object {
		val WAVE_1A = listOf("C", "S", "Y", "R", "O")
		val WAVE_1B = WAVE_1A + listOf("L", "H", "W", "F", "P", "M", "D")
	}

	@Test fun theRuntimeRegistersTheBuiltMethodsWithTheRightKinds() {
		val kinds = FallbackRuntime.methods.associate { it.id to it.kind }
		assertEquals(setOf("C", "Y", "S", "R", "O", "L", "H", "W", "F", "P", "M", "D", "N"), kinds.keys)                 // grows with every method task
		assertEquals(FallbackMethod.Kind.RESOLVER, kinds["R"])
		for (id in listOf("C", "Y", "S", "O", "L", "H", "W", "F", "P", "M", "D", "N")) assertEquals(id, FallbackMethod.Kind.EXECUTOR, kinds[id])
	}
}
