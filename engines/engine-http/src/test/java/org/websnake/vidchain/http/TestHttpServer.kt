package org.websnake.vidchain.http

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Minimal HTTP/1.1 test server on a loopback socket (android.jar has no com.sun HttpServer): one request per connection. */
class TestHttpServer : AutoCloseable {
	class Ex(val method: String, val path: String, private val headers: Map<String, String>, val out: OutputStream) {
		fun header(name: String) = headers[name.lowercase()]
		fun send(code: Int, body: ByteArray = ByteArray(0), length: Long = body.size.toLong(), headers: Map<String, String> = emptyMap()) {
			val sb = StringBuilder("HTTP/1.1 $code X\r\nConnection: close\r\n")
			if (length >= 0) sb.append("Content-Length: $length\r\n")
			headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
			out.write(sb.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush()
		}
	}

	private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
	private val routes = ConcurrentHashMap<String, (Ex, Int) -> Unit>()
	private val counters = ConcurrentHashMap<String, AtomicInteger>()
	val requests: MutableList<Pair<String, Map<String, String>>> = Collections.synchronizedList(ArrayList())

	init {
		Thread {
			while (!server.isClosed) {
				val s = runCatching { server.accept() }.getOrNull() ?: break
				Thread { serve(s) }.apply { isDaemon = true }.start()
			}
		}.apply { isDaemon = true }.start()
	}

	fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
	fun route(path: String, h: (Ex, Int) -> Unit) { routes[path] = h }

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
		requests += path to headers
		val h = routes[path.substringBefore('?')] ?: return Ex(method, path, headers, sock.getOutputStream()).send(404)
		try { h(Ex(method, path, headers, sock.getOutputStream()), counters.getOrPut(path.substringBefore('?')) { AtomicInteger() }.incrementAndGet()) } catch (e: Exception) { }
	}

	override fun close() = server.close()
}
