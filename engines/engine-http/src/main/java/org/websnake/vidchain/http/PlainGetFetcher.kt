package org.websnake.vidchain.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Method O: one plain GET stream. Unlike the current Regular downloader it needs no HEAD, no Range support and no
 * Content-Length. It resumes only when the server proved it can (Accept-Ranges / 206 with a validator), otherwise it
 * restarts. The caller (the fallback coordinator) verifies and commits the finished temp file.
 */
class PlainGetFetcher(
	private val client: OkHttpClient,
	private val backoffMs: (Int) -> Long = { attempt -> 1_000L shl attempt.coerceAtMost(4) },
) {
	data class Fetch(
		val url: String,
		val headers: Map<String, String> = emptyMap(),
		val temp: File,
		val maxRetries: Int = 3,
		/** looks at the first bytes of a fresh download; a non-null answer aborts it (an HTML page instead of a file) */
		val early: ((ByteArray) -> String?)? = null,
		/**
		 * Cookie header per request URL, asked again on every redirect hop, so cookies only reach their own host.
		 * (A Cookie in [headers] would follow redirects to other hosts: OkHttp keeps hand-set headers.)
		 */
		val cookieFor: ((String) -> String?)? = null,
	) {
		init { require(headers.keys.none { it.equals("Cookie", ignoreCase = true) }) { "pass cookies through cookieFor" } }
	}

	sealed class Result {
		data class Done(val file: File, val bytes: Long, val contentType: String?, val suggestedName: String, val finalUrl: String, val resumed: Boolean) : Result()
		data class Failed(val reason: String, val httpCode: Int? = null) : Result()
	}

	private data class Meta(val url: String, val validator: String?, val resumable: Boolean, val total: Long?)

	suspend fun fetch(f: Fetch, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Result {
		var lastError = "no attempt"
		var lastCode: Int? = null
		var resumedAny = false
		for (attempt in 0..f.maxRetries) {
			if (attempt > 0) delay(backoffMs(attempt - 1))
			val meta = readMeta(f.temp)
			var have = if (meta != null && meta.url == f.url && meta.resumable && f.temp.isFile) f.temp.length() else 0L
			if (have == 0L) { f.temp.delete(); metaFile(f.temp).delete() }
			val rb = Request.Builder().url(f.url).get()
			for ((k, v) in f.headers) rb.header(k, v)
			rb.header("Accept-Encoding", "identity")       // byte ranges and sizes refer to the raw file
			if (have > 0) {
				rb.header("Range", "bytes=$have-")
				meta?.validator?.let { rb.header("If-Range", it) }
			}
			val callClient = f.cookieFor?.let { cookieFor ->
				client.newBuilder().addNetworkInterceptor { chain ->
					val req = chain.request()
					val c = cookieFor(req.url.toString())
					chain.proceed(if (c != null) req.newBuilder().header("Cookie", c).build() else req.newBuilder().removeHeader("Cookie").build())
				}.build()
			} ?: client
			val response = try {
				callClient.newCall(rb.build()).await()
			} catch (e: IOException) {
				lastError = "connection: ${e.javaClass.simpleName}: ${e.message}"; continue
			}
			response.use { r ->
				val code = r.code
				lastCode = code
				when {
					code == 416 && have > 0 -> {
						val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
						if (total == have) return done(f, have, r, resumed = true)
						f.temp.delete(); metaFile(f.temp).delete()
						lastError = "range not satisfiable"; return@use
					}
					code == 206 && have > 0 && r.header("Content-Range")?.startsWith("bytes $have-") == true -> resumedAny = true
					code == 206 -> {
						// a range we did not ask for: never stitch it in, start over without Range
						f.temp.delete(); metaFile(f.temp).delete()
						lastError = "unexpected range ${r.header("Content-Range")}"; return@use
					}
					code in 200..299 -> have = 0L
					else -> {
						lastError = "HTTP $code"
						if (!retryable(code)) return Result.Failed(lastError, code)
						return@use
					}
				}
				val body = r.body
				val length = body.contentLength().takeIf { it >= 0 }
				val total = length?.let { it + have }
				val validator = r.header("ETag")?.takeUnless { it.startsWith("W/") } ?: r.header("Last-Modified")
				val resumable = validator != null && (r.header("Accept-Ranges")?.contains("bytes") == true || code == 206)
				writeMeta(f.temp, Meta(f.url, validator, resumable, total))
				try {
					val written = withContext(Dispatchers.IO) { stream(r, f, have, total, onProgress) }
					if (written is String) { f.temp.delete(); metaFile(f.temp).delete(); return Result.Failed(written) }
					val size = f.temp.length()
					if (total != null && size != total) { lastError = "short read: $size of $total bytes"; return@use }
					return done(f, size, r, resumedAny)
				} catch (e: IOException) {
					lastError = "stream: ${e.javaClass.simpleName}: ${e.message}"
					if (!resumable) { f.temp.delete(); metaFile(f.temp).delete() }
				}
			}
		}
		return Result.Failed(lastError, lastCode)
	}

	/** returns the byte count, or a String when the early check rejected the content */
	private suspend fun stream(r: Response, f: Fetch, have: Long, total: Long?, onProgress: (Long, Long?) -> Unit): Any {
		val ctx = kotlin.coroutines.coroutineContext
		f.temp.parentFile?.mkdirs()
		var done = have
		FileOutputStream(f.temp, have > 0).use { out ->
			val input = r.body.byteStream()
			val buf = ByteArray(64 * 1024)
			val head = if (have == 0L && f.early != null) java.io.ByteArrayOutputStream(EARLY_BYTES) else null
			while (true) {
				ctx.ensureActive()
				val n = input.read(buf)
				if (n < 0) break
				out.write(buf, 0, n)
				done += n
				if (head != null && head.size() < EARLY_BYTES) {
					head.write(buf, 0, minOf(n, EARLY_BYTES - head.size()))
					if (head.size() >= EARLY_BYTES) f.early!!(head.toByteArray())?.let { return it }
				}
				onProgress(done, total)
			}
			if (head != null && head.size() in 1 until EARLY_BYTES) f.early!!(head.toByteArray())?.let { return it }
		}
		return done
	}

	private fun done(f: Fetch, bytes: Long, r: Response, resumed: Boolean): Result.Done {
		metaFile(f.temp).delete()
		val type = r.header("Content-Type")
		return Result.Done(f.temp, bytes, type, suggestName(r.header("Content-Disposition"), r.request.url.toString(), type), r.request.url.toString(), resumed)
	}

	private fun retryable(code: Int) = code == 408 || code == 425 || code == 429 || code in 500..599

	private fun metaFile(temp: File) = File(temp.path + ".meta")

	private fun readMeta(temp: File): Meta? = runCatching {
		val p = java.util.Properties().apply { metaFile(temp).inputStream().use { load(it) } }
		Meta(p.getProperty("url"), p.getProperty("validator"), p.getProperty("resumable") == "true", p.getProperty("total")?.toLongOrNull())
	}.getOrNull()

	private fun writeMeta(temp: File, m: Meta) {
		temp.parentFile?.mkdirs()
		val p = java.util.Properties()
		p.setProperty("url", m.url); p.setProperty("resumable", m.resumable.toString())
		m.validator?.let { p.setProperty("validator", it) }
		m.total?.let { p.setProperty("total", it.toString()) }
		metaFile(temp).outputStream().use { p.store(it, null) }
	}

	companion object {
		const val EARLY_BYTES = 4096

		/** Content-Disposition filename*= / filename=, else the URL's last path segment, else "download" + an extension from the type */
		fun suggestName(disposition: String?, url: String, contentType: String?): String {
			disposition?.let { d ->
				Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(d)?.let { m ->
					runCatching { URLDecoder.decode(m.groupValues[1].trim('"'), "UTF-8") }.getOrNull()?.let { return clean(it) }   // a malformed % falls through
				}
				Regex("""filename\s*=\s*"?([^";]+)"?""").find(d)?.let { return clean(it.groupValues[1]) }
			}
			val path = runCatching { java.net.URI(url).rawPath }.getOrNull().orEmpty()
			val seg = path.trimEnd('/').substringAfterLast('/')
			val decoded = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
			if (decoded.contains('.') && decoded.length <= 200) return clean(decoded)
			val base = clean(decoded.ifEmpty { "download" })
			return base + extensionFor(contentType)
		}

		fun extensionFor(contentType: String?): String = when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
			"video/mp4" -> ".mp4"; "video/webm" -> ".webm"; "video/x-matroska" -> ".mkv"; "video/quicktime" -> ".mov"
			"video/mp2t" -> ".ts"; "video/x-flv" -> ".flv"; "video/3gpp" -> ".3gp"
			"audio/mpeg" -> ".mp3"; "audio/mp4", "audio/x-m4a" -> ".m4a"; "audio/aac" -> ".aac"; "audio/ogg" -> ".ogg"
			"audio/webm" -> ".weba"; "audio/flac" -> ".flac"; "audio/wav", "audio/x-wav" -> ".wav"
			"application/zip" -> ".zip"; "application/pdf" -> ".pdf"; "application/vnd.android.package-archive" -> ".apk"
			else -> ""
		}

		private fun clean(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "download" }
	}
}

/** OkHttp call as a cancellable suspend function (cancelling the coroutine cancels the call) */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
	cont.invokeOnCancellation { runCatching { cancel() } }
	enqueue(object : Callback {
		override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
		override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
	})
}
