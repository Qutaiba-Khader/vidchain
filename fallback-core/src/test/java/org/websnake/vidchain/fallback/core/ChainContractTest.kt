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

	private fun order(url: String): List<String> = runBlocking {
		val log = ArrayList<String>()
		val wave1a = listOf("C" to FallbackMethod.Kind.EXECUTOR, "S" to FallbackMethod.Kind.EXECUTOR, "Y" to FallbackMethod.Kind.EXECUTOR,
			"R" to FallbackMethod.Kind.RESOLVER, "O" to FallbackMethod.Kind.EXECUTOR).map { (id, k) -> Fail(id, k, log) }
		val host = Host(url)
		val c = FallbackCoordinator(host, InMemoryLedger(), wave1a, this, FallbackCoordinator.Config(includeStub = false))
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

	@Test fun everyChainIsAPrefixFreeOrderOfKnownMethods() {
		val known = setOf("C", "S", "Y", "P", "F", "N", "W", "R", "H", "G", "U", "T", "X", "L", "O", "A", "D", "M")
		for ((chain, steps) in ChainSpec.chains) {
			assertEquals(chain, steps.size, steps.toSet().size)
			assertEquals(chain, emptySet<String>(), steps.toSet() - known)
		}
	}

	@Test fun theRuntimeRegistersTheBuiltMethodsWithTheRightKinds() {
		val kinds = FallbackRuntime.methods.associate { it.id to it.kind }
		assertEquals(setOf("C", "Y", "S", "R", "O", "L", "H"), kinds.keys)                 // grows with every method task
		assertEquals(FallbackMethod.Kind.RESOLVER, kinds["R"])
		for (id in listOf("C", "Y", "S", "O", "L", "H")) assertEquals(id, FallbackMethod.Kind.EXECUTOR, kinds[id])
	}
}
