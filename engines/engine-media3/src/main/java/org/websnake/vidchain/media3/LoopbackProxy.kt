package org.websnake.vidchain.media3

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Where the proxy reads from (Media3's cache in the app, a map in tests). null = not available. */
fun interface ContentStore {
	fun open(url: String): InputStream?
}

/**
 * A short-lived HTTP server on 127.0.0.1 that hands ffmpeg what Media3 already downloaded. URLs keep their shape
 * (`/<token>/<scheme>/<host[:port]>/<path>?<query>`), so relative segment URLs inside a manifest resolve back through
 * the proxy; absolute URLs in manifests are rewritten. A random token keeps other apps on the phone out.
 */
class LoopbackProxy(private val store: ContentStore) : AutoCloseable {
	private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
	private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
	private val open = AtomicBoolean(true)
	val requests = java.util.Collections.synchronizedList(ArrayList<String>())

	init {
		Thread({
			while (open.get()) {
				val s = runCatching { server.accept() }.getOrNull() ?: break
				Thread({ serve(s) }, "vidchain-proxy").apply { isDaemon = true }.start()
			}
		}, "vidchain-proxy-accept").apply { isDaemon = true }.start()
	}

	private val prefix get() = "http://127.0.0.1:${server.localPort}/$token/"

	/** the proxy URL that serves [url] */
	fun proxied(url: String): String {
		val u = URI(url)
		val hostPort = u.host + if (u.port > 0) ":${u.port}" else ""
		return prefix + u.scheme + "/" + hostPort + (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
	}

	/** the original URL behind a proxy path, or null for a wrong token */
	internal fun original(path: String): String? {
		val p = path.removePrefix("/")
		if (!p.startsWith("$token/")) return null
		val rest = p.removePrefix("$token/")
		val scheme = rest.substringBefore('/')
		if (scheme != "http" && scheme != "https") return null
		return "$scheme://" + rest.substringAfter('/')
	}

	private fun serve(sock: Socket) = sock.use {
		val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
		val first = input.readLine() ?: return
		while (input.readLine()?.isNotEmpty() == true) Unit
		val out = sock.getOutputStream()
		val target = first.split(' ').getOrNull(1)?.let(::original)
		requests += target ?: "denied"
		val body = target?.let { runCatching { store.open(it) }.getOrNull() }
		if (body == null) {
			out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
		}
		body.use { b ->
			val bytes = b.readBytes()
			val payload = if (isManifest(target, bytes)) rewrite(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes
			out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
			out.write(payload); out.flush()
		}
	}

	private fun isManifest(url: String, bytes: ByteArray): Boolean {
		val path = url.substringBefore('?').lowercase()
		if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return true
		val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1).trimStart()
		return head.startsWith("#EXTM3U") || head.startsWith("<?xml") && "<MPD" in String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1) || head.startsWith("<MPD")
	}

	/** absolute http(s) URLs inside a manifest go through the proxy too (relative ones already do) */
	internal fun rewrite(manifest: String): String =
		Regex("""https?://[^\s"'<>#]+""").replace(manifest) { m -> runCatching { proxied(m.value.replace("&amp;", "&")).let { p -> if ("&amp;" in m.value) p.replace("&", "&amp;") else p } }.getOrDefault(m.value) }

	override fun close() { open.set(false); runCatching { server.close() } }
}
