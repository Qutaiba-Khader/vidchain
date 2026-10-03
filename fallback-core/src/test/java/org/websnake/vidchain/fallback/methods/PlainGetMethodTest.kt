package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.Engine
import org.websnake.vidchain.fallback.classifier.StatusKey
import org.websnake.vidchain.fallback.core.Candidate
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackCoordinator
import org.websnake.vidchain.fallback.core.FallbackHost
import org.websnake.vidchain.fallback.core.HostDownload
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.ledger.AttemptState
import org.websnake.vidchain.fallback.ledger.InMemoryLedger
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** Method O end to end: the current method failed, the chain reaches O, O fetches from a server with no HEAD and no length. */
class PlainGetMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var server: ServerSocket
	@Volatile private var body: ByteArray = ByteArray(0)
	@Volatile private var heads = 0

	private fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
	private val mp4 = box("ftyp", 8) + box("moov", 300) + box("mdat", 6000)

	@Before fun start() {
		server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
		Thread {
			while (!server.isClosed) {
				val s = runCatching { server.accept() }.getOrNull() ?: break
				Thread {
					s.use {
						val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
						val first = r.readLine() ?: return@use
						while (r.readLine()?.isNotEmpty() == true) Unit
						val out = s.getOutputStream()
						if (first.startsWith("HEAD")) { heads++; out.write("HTTP/1.1 405 No\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); return@use }
						out.write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: video/mp4\r\n\r\n".toByteArray())   // no length
						out.write(body); out.flush()
					}
				}.start()
			}
		}.apply { isDaemon = true }.start()
	}

	@After fun stop() { server.close() }

	private val url get() = "http://127.0.0.1:${server.localPort}/media/clip.mp4"
	private val method = PlainGetMethod(PlainGetFetcher(OkHttpClient(), backoffMs = { 0 }), DeliveryVerifier())

	@Test fun refusesWhatIsNotOneHttpFile() = runBlocking {
		val ctx = FallbackContext("1", "magnet:?xt=1", "magnet:?xt=1", UrlClass.MAGNET_TORRENT, destPath = tmp.root.path + "/a.mp4")
		assertTrue(method.attempt(ctx) is MethodOutcome.Unsupported)
		assertTrue(method.attempt(ctx.copy(url = url, mediaUrl = url, urlClass = UrlClass.HLS_DASH)) is MethodOutcome.Unsupported)
		assertTrue(method.attempt(ctx.copy(url = url, mediaUrl = url, urlClass = UrlClass.DIRECT_FILE, destPath = null)) is MethodOutcome.Unsupported)
	}

	@Test fun errorPageIsRejectedEarlyAndLeavesNothingBehind() = runBlocking {
		body = "<!doctype html><html><body>".plus("x".repeat(5000)).toByteArray()
		val dest = File(tmp.root, "out/clip.mp4")
		val r = method.attempt(FallbackContext("1", url, url, UrlClass.DIRECT_FILE, destPath = dest.path))
		assertTrue(r is MethodOutcome.Failed)
		assertFalse(File(dest.parentFile, "${PlainGetMethod.PARTIAL_DIR}/1-O.part").exists())
	}

	@Test fun chainReachesOAndTheFileIsVerifiedCommittedAndListed() = runBlocking {
		body = mp4
		val dest = File(tmp.root, "Downloads/clip.mp4").apply { parentFile!!.mkdirs() }
		val listed = ArrayList<File>()
		val host = object : FallbackHost {
			val list = LinkedHashMap<String, HostDownload>()
			override fun downloads() = list.values.toList()
			override fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String? = null
			override fun registerDelivered(parent: HostDownload, file: File, method: String): String { listed += file; return "900" }
			fun set(s: DownloadSnapshot) { list["1"] = HostDownload("1", url, url, s, filePath = dest.path) }
		}
		val ledger = InMemoryLedger()
		val c = FallbackCoordinator(host, ledger, listOf(method), this, FallbackCoordinator.Config(includeStub = false))
		host.set(DownloadSnapshot(Engine.REGULAR, DownloadSnapshot.DOWNLOADING, isRunning = true)); c.tick()
		host.set(DownloadSnapshot(Engine.REGULAR, DownloadSnapshot.CLOSE, statusKey = StatusKey.DOWNLOAD_FAILED)); c.tick(); c.drain()
		val rows = ledger.attempts("1")
		assertEquals(listOf("O"), rows.map { it.method })
		assertEquals(AttemptState.DELIVERED, rows[0].state)
		assertEquals("900", rows[0].childId)
		assertEquals(listOf(dest), listed)
		assertTrue(dest.readBytes().contentEquals(mp4))
		assertEquals(0, heads)
		assertFalse(File(dest.parentFile, "${PlainGetMethod.PARTIAL_DIR}/1-O.part").exists())
	}
}
