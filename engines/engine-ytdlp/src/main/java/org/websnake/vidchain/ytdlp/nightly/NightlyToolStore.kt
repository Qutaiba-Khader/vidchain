package org.websnake.vidchain.ytdlp.nightly

import okhttp3.OkHttpClient
import okhttp3.Request
import org.websnake.vidchain.engine.KeyValueStore
import org.websnake.vidchain.http.await
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Method N's tool store (T4.1): the official yt-dlp nightly zipapp in its own versioned folder, never the library's
 * bundled yt-dlp (which the app's own updater manages; N never calls it). A download is installed only when its
 * SHA-256 matches the release's SHA2-256SUMS; the previous version stays for rollback; GitHub is asked at most once
 * per [checkEveryMs].
 */
class NightlyToolStore(
	private val root: File,
	private val client: OkHttpClient,
	private val kv: KeyValueStore,
	private val base: String = "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases",
	private val clock: () -> Long = System::currentTimeMillis,
	private val checkEveryMs: Long = 24 * 3_600_000L,
	private val maxBytes: Long = 64L * 1024 * 1024,
) {
	sealed class Result {
		data class Ready(val file: File, val version: String, val fresh: Boolean) : Result()
		data class Failed(val reason: String) : Result()
	}

	/** the installed nightly, refreshed when the last check is older than a day; on any update problem the current one stays */
	suspend fun current(): Result {
		val installed = installed()
		val due = clock() - (kv.get(KEY_CHECKED)?.toLongOrNull() ?: 0L) >= checkEveryMs
		if (installed != null && !due) return Result.Ready(installed.second, installed.first, false)
		val update = try { update() } catch (e: IOException) { Result.Failed("update: ${e.javaClass.simpleName}: ${e.message}") }
		kv.put(KEY_CHECKED, clock().toString())
		return when {
			update is Result.Ready -> update
			installed != null -> Result.Ready(installed.second, installed.first, false)
			else -> update
		}
	}

	/** asks GitHub for the latest nightly; installs it when new and verified */
	suspend fun update(): Result {
		val version = latestVersion() ?: return Result.Failed("no latest nightly version")
		if (!Regex("""[0-9A-Za-z._-]{4,64}""").matches(version)) return Result.Failed("unexpected version name")
		installed()?.takeIf { it.first == version }?.let { return Result.Ready(it.second, version, false) }
		val sums = String(get("$base/download/$version/SHA2-256SUMS", 1L shl 20), Charsets.UTF_8)
		val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size >= 2 && it[1].trimStart('*') == ASSET }?.get(0)?.lowercase()
			?: return Result.Failed("SHA2-256SUMS has no line for $ASSET")
		val bytes = get("$base/download/$version/$ASSET", maxBytes)
		val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
		if (actual != expected) return Result.Failed("checksum mismatch for $version: refused")
		val dir = File(root, version).apply { mkdirs() }
		val tmp = File(dir, "$ASSET.tmp").apply { writeBytes(bytes) }
		val file = File(dir, ASSET)
		if (!tmp.renameTo(file)) { tmp.delete(); return Result.Failed("could not install $version") }
		kv.get(KEY_CURRENT)?.takeIf { it != version }?.let { kv.put(KEY_PREVIOUS, it) }
		kv.put(KEY_CURRENT, version)
		prune()
		return Result.Ready(file, version, true)
	}

	/** back to the previous nightly (e.g. after the new one keeps failing) */
	fun rollback(): Boolean {
		val prev = kv.get(KEY_PREVIOUS)?.takeIf { File(root, "$it/$ASSET").isFile } ?: return false
		kv.get(KEY_CURRENT)?.let { kv.put(KEY_PREVIOUS, it) }
		kv.put(KEY_CURRENT, prev)
		return true
	}

	fun installed(): Pair<String, File>? = kv.get(KEY_CURRENT)?.let { v -> File(root, "$v/$ASSET").takeIf { it.isFile }?.let { v to it } }

	/** keeps the current and the previous version folders only */
	private fun prune() {
		val keep = setOfNotNull(kv.get(KEY_CURRENT), kv.get(KEY_PREVIOUS))
		root.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
	}

	/** the tag that /releases/latest redirects to */
	private suspend fun latestVersion(): String? {
		val c = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
		return c.newCall(Request.Builder().url("$base/latest").get().build()).await().use { r ->
			r.header("Location")?.substringAfterLast("/tag/", "")?.substringBefore('?')?.takeIf { it.isNotEmpty() }
		}
	}

	private suspend fun get(url: String, cap: Long): ByteArray =
		client.newCall(Request.Builder().url(url).get().build()).await().use { r ->
			if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${url.substringAfterLast('/')}")
			val len = r.body.contentLength()
			if (len > cap) throw IOException("too large")
			val src = r.body.source()
			src.request(cap + 1)
			if (src.buffer.size > cap) throw IOException("too large")
			src.readByteArray()
		}

	companion object {
		const val ASSET = "yt-dlp"
		const val KEY_CURRENT = "nightly.current"
		const val KEY_PREVIOUS = "nightly.previous"
		const val KEY_CHECKED = "nightly.checked"
	}
}
