package org.websnake.vidchain.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A local HTTP server plays every awkward server the plain GET method has to cope with. */
class PlainGetFetcherTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var server: ServerSocket
	private val requests = Collections.synchronizedList(ArrayList<Map<String, String?>>())
	private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
	private val fetcher = PlainGetFetcher(client, backoffMs = { 0 })
	private val payload = ByteArray(50_000) { (it * 7 + 3).toByte() }

	/** minimal HTTP/1.1 server: one request per connection, Connection: close */
	class Ex(val method: String, val path: String, private val reqHeaders: Map<String, String>, val out: OutputStream) {
		fun header(name: String) = reqHeaders[name.lowercase()]
		fun send(code: Int, body: ByteArray, length: Long = body.size.toLong(), headers: Map<String, String> = emptyMap()) {
			val sb = StringBuilder("HTTP/1.1 $code X\r\nConnection: close\r\n")
			if (length >= 0) sb.append("Content-Length: $length\r\n")       // length < 0: no length, body ends at close
			headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
			out.write(sb.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
			out.write(body); out.flush()
		}
	}

	private val routes = java.util.concurrent.ConcurrentHashMap<String, (Ex, Int) -> Unit>()
	private val counters = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

	@Before fun start() {
		server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
		Thread {
			while (!server.isClosed) {
				val sock = runCatching { server.accept() }.getOrNull() ?: break
				Thread { serve(sock) }.apply { isDaemon = true }.start()
			}
		}.apply { isDaemon = true }.start()
	}

	@After fun stop() { server.close() }

	private fun serve(sock: Socket) = sock.use {
		val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
		val first = input.readLine() ?: return
		val headers = HashMap<String, String>()
		while (true) {
			val l = input.readLine() ?: break
			if (l.isEmpty()) break
			headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
		}
		val (method, path) = first.split(' ').let { it[0] to it[1] }
		requests += mapOf("method" to method, "range" to headers["range"], "ifRange" to headers["if-range"], "ua" to headers["user-agent"])
		val h = routes[path.substringBefore('?')] ?: return
		try { h(Ex(method, path, headers, sock.getOutputStream()), counters.getOrPut(path) { AtomicInteger() }.incrementAndGet()) } catch (e: Exception) { }
	}

	private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
	private fun route(path: String, h: (Ex, Int) -> Unit) { routes[path] = h }

	private fun temp() = java.io.File(tmp.root, "part/x.part")
	private suspend fun get(path: String, headers: Map<String, String> = emptyMap(), early: ((ByteArray) -> String?)? = null) =
		fetcher.fetch(PlainGetFetcher.Fetch(url(path), headers, temp(), early = early))

	@Test fun noHeadUnknownLengthExtensionless() = runBlocking {
		route("/v/abc123") { ex, _ ->
			if (ex.method == "HEAD") ex.send(405, ByteArray(0))
			else ex.send(200, payload, length = -1, headers = mapOf("Content-Type" to "video/mp4"))
		}
		val r = get("/v/abc123", mapOf("User-Agent" to "VidChainTest")) as PlainGetFetcher.Result.Done
		assertArrayEquals(payload, r.file.readBytes())
		assertEquals("abc123.mp4", r.suggestedName)
		assertEquals(listOf("GET"), requests.map { it["method"] })          // never a HEAD
		assertEquals("VidChainTest", requests[0]["ua"])
	}

	@Test fun contentDispositionNameWins() = runBlocking {
		route("/dl") { ex, _ -> ex.send(200, payload, headers = mapOf("Content-Disposition" to "attachment; filename*=UTF-8''Caf%C3%A9%20clip.mp4")) }
		assertEquals("Café clip.mp4", (get("/dl") as PlainGetFetcher.Result.Done).suggestedName)
	}

	@Test fun resumesWhenTheServerCanAndRestartsWhenItCannot() = runBlocking {
		route("/resumable") { ex, n ->
			val h = mapOf("ETag" to "\"v1\"", "Accept-Ranges" to "bytes")
			if (n == 1) { ex.send(200, payload.copyOf(10_000), length = payload.size.toLong(), headers = h); throw RuntimeException("drop") }
			val from = ex.header("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
			ex.send(206, payload.copyOfRange(from, payload.size), headers = h + ("Content-Range" to "bytes $from-${payload.size - 1}/${payload.size}"))
		}
		val r = get("/resumable") as PlainGetFetcher.Result.Done
		assertTrue(r.resumed)
		assertArrayEquals(payload, r.file.readBytes())
		assertEquals("bytes=10000-", requests[1]["range"]); assertEquals("\"v1\"", requests[1]["ifRange"])
		requests.clear()
		route("/plain") { ex, n ->
			if (n == 1) { ex.send(200, payload.copyOf(10_000), length = payload.size.toLong()); throw RuntimeException("drop") }
			ex.send(200, payload)
		}
		val p = get("/plain") as PlainGetFetcher.Result.Done
		assertFalse(p.resumed)
		assertArrayEquals(payload, p.file.readBytes())
		assertNull(requests[1]["range"])                                     // no validator: no Range, start over
	}

	@Test fun unexpectedRangeIsNeverStitchedIn() = runBlocking {
		route("/badrange") { ex, n ->
			val h = mapOf("ETag" to "\"v1\"", "Accept-Ranges" to "bytes")
			when (n) {
				1 -> { ex.send(200, payload.copyOf(10_000), length = payload.size.toLong(), headers = h); throw RuntimeException("drop") }
				2 -> ex.send(206, payload.copyOfRange(0, 100), headers = h + ("Content-Range" to "bytes 0-99/${payload.size}"))
				else -> ex.send(200, payload, headers = h)
			}
		}
		val r = get("/badrange") as PlainGetFetcher.Result.Done
		assertArrayEquals(payload, r.file.readBytes())
		assertNull(requests[2]["range"])
	}

	@Test fun httpErrorsFailFastAndServerErrorsAreRetried() = runBlocking {
		route("/gone") { ex, _ -> ex.send(404, "nope".toByteArray()) }
		assertEquals(PlainGetFetcher.Result.Failed("HTTP 404", 404), get("/gone"))
		assertEquals(1, requests.size)
		route("/flaky") { ex, n -> if (n < 3) ex.send(503, "busy".toByteArray()) else ex.send(200, payload) }
		assertTrue(get("/flaky") is PlainGetFetcher.Result.Done)
	}

	@Test fun earlyCheckAbortsAnErrorPageAndRemovesIt() = runBlocking {
		route("/page") { ex, _ -> ex.send(200, "<!doctype html><html><body>Please log in</body></html>".toByteArray()) }
		val r = get("/page", early = { head -> if (String(head).startsWith("<!doctype html")) "HTML instead of the file" else null })
		assertEquals(PlainGetFetcher.Result.Failed("HTML instead of the file"), r)
		assertFalse(temp().exists())
	}

	@Test fun alwaysShortGivesUpAfterTheRetries() = runBlocking {
		route("/short") { ex, _ -> ex.send(200, payload.copyOf(100), length = payload.size.toLong()); throw RuntimeException("drop") }
		val r = get("/short")
		assertTrue(r is PlainGetFetcher.Result.Failed)
		assertEquals(4, requests.size)                                       // 1 + 3 retries
	}

	@Test fun cancelStopsTheTransfer() = runBlocking {
		route("/slow") { ex, _ ->
			ex.send(200, ByteArray(0), length = -1)
			repeat(600) { ex.out.write(ByteArray(1024)); ex.out.flush(); Thread.sleep(50) }
		}
		val t0 = System.currentTimeMillis()
		val job = launch(Dispatchers.Default) { get("/slow") }
		delay(500)
		job.cancelAndJoin()
		assertTrue(System.currentTimeMillis() - t0 < 5_000)
	}

	@Test fun names() {
		assertEquals("clip.webm", PlainGetFetcher.suggestName("attachment; filename=\"clip.webm\"", "http://x/y", null))
		assertEquals("video.mp4", PlainGetFetcher.suggestName(null, "http://x/a/video.mp4?sig=1", null))
		assertEquals("download.mp3", PlainGetFetcher.suggestName(null, "http://x/", "audio/mpeg"))
		assertEquals("a_b.mp4", PlainGetFetcher.suggestName("attachment; filename=\"a/b.mp4\"", "http://x/", null))
	}
}
