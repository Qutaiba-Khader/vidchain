package org.websnake.vidchain.hosts

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.websnake.vidchain.http.await
import java.io.IOException
import java.util.Base64
import java.util.Locale

/** A fetched page (first 512 KB of the body). */
data class Page(val code: Int, val url: String, val body: String, val contentType: String?)

/** What a resolver found. Headers never carry cookies (the fetcher scopes those itself). */
sealed class HostResult {
	data class Direct(val url: String, val headers: Map<String, String> = emptyMap(), val fileName: String? = null) : HostResult()
	data class NeedsLogin(val reason: String) : HostResult()
	data class Failed(val reason: String) : HostResult()
	object NotMine : HostResult()
}

/** One share-link family. [fetch] is the only network access, so recorded pages can stand in for the real sites. */
interface FileHostResolver {
	val name: String
	fun matches(url: HttpUrl): Boolean
	suspend fun resolve(url: HttpUrl, fetch: suspend (String) -> Page): HostResult
}

/** Method L's resolvers (T3.1): Dropbox, Pixeldrain, Google Drive, MediaFire, OneDrive. */
object FileHosts {
	val all: List<FileHostResolver> = listOf(Dropbox, Pixeldrain, GoogleDrive, MediaFire, OneDrive)

	suspend fun resolve(url: String, fetch: suspend (String) -> Page): HostResult {
		val u = url.toHttpUrlOrNull() ?: return HostResult.NotMine
		val r = all.firstOrNull { it.matches(u) } ?: return HostResult.NotMine
		return try { r.resolve(u, fetch) } catch (e: IOException) { HostResult.Failed("${r.name}: ${e.javaClass.simpleName}") }
	}

	/** a page fetcher on an OkHttp client: GET, redirects followed, body capped */
	fun fetcher(client: OkHttpClient, userAgent: String?): suspend (String) -> Page = { url ->
		val rb = Request.Builder().url(url).get()
		userAgent?.let { rb.header("User-Agent", it) }
		client.newCall(rb.build()).await().use { r ->
			val src = r.body.source()
			src.request(MAX_PAGE)
			Page(r.code, r.request.url.toString(), src.buffer.snapshot().utf8().take(MAX_PAGE.toInt()), r.header("Content-Type"))
		}
	}

	private const val MAX_PAGE = 512L * 1024

	internal fun hostIs(u: HttpUrl, vararg domains: String) = domains.any { d -> u.host.equals(d, true) || u.host.lowercase(Locale.ROOT).endsWith(".$d") }
	internal fun loginWall(p: Page) = p.code == 401 || p.code == 403 && "sign in" in p.body.lowercase() ||
		Regex("""accounts\.google\.com/(ServiceLogin|v3/signin)|login\.live\.com|/login\?""").containsMatchIn(p.url)
	internal fun captcha(body: String) = Regex("""g-recaptcha|h-captcha|cf-turnstile|captcha""", RegexOption.IGNORE_CASE).containsMatchIn(body)
	internal fun unescape(s: String) = s.replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/")
}

/** Dropbox share links: dl=1 asks for the file instead of the preview page; rlkey must stay. */
object Dropbox : FileHostResolver {
	override val name = "Dropbox"
	override fun matches(url: HttpUrl) = FileHosts.hostIs(url, "dropbox.com") && (url.encodedPath.startsWith("/s/") || url.encodedPath.startsWith("/scl/") || url.encodedPath.startsWith("/sh/"))
	override suspend fun resolve(url: HttpUrl, fetch: suspend (String) -> Page): HostResult {
		if (url.encodedPath.startsWith("/sh/")) return HostResult.Failed("Dropbox folder link: not one file")
		return HostResult.Direct(url.newBuilder().setQueryParameter("dl", "1").build().toString(), fileName = url.pathSegments.lastOrNull())
	}
}

/** Pixeldrain: /u/<id> -> /api/file/<id>?download (lists /l/ are not one file). */
object Pixeldrain : FileHostResolver {
	override val name = "Pixeldrain"
	override fun matches(url: HttpUrl) = FileHosts.hostIs(url, "pixeldrain.com", "pixeldra.in")
	override suspend fun resolve(url: HttpUrl, fetch: suspend (String) -> Page): HostResult {
		val seg = url.pathSegments
		if (seg.size >= 2 && seg[0] == "l") return HostResult.Failed("Pixeldrain list: not one file")
		val id = when {
			seg.size >= 2 && seg[0] == "u" -> seg[1]
			seg.size >= 3 && seg[0] == "api" && seg[1] == "file" -> seg[2]
			else -> return HostResult.Failed("Pixeldrain: no file id")
		}
		if (!Regex("[A-Za-z0-9]{4,}").matches(id)) return HostResult.Failed("Pixeldrain: bad id")
		return HostResult.Direct("https://pixeldrain.com/api/file/$id?download")
	}
}

/** Google Drive: file id -> drive.usercontent download; large files answer with a confirm form that is submitted. */
object GoogleDrive : FileHostResolver {
	override val name = "Google Drive"
	override fun matches(url: HttpUrl) = FileHosts.hostIs(url, "drive.google.com", "docs.google.com", "drive.usercontent.google.com")

	fun fileId(url: HttpUrl): String? =
		Regex("""/(?:file/)?d/([A-Za-z0-9_-]{10,})""").find(url.encodedPath)?.groupValues?.get(1) ?: url.queryParameter("id")?.takeIf { it.length >= 10 }

	override suspend fun resolve(url: HttpUrl, fetch: suspend (String) -> Page): HostResult {
		if (url.encodedPath.contains("/folders/")) return HostResult.Failed("Google Drive folder: not one file")
		if (url.host.startsWith("docs.") && (url.encodedPath.startsWith("/document") || url.encodedPath.startsWith("/spreadsheets"))) return HostResult.Failed("Google Docs: not a media file")
		val id = fileId(url) ?: return HostResult.Failed("Google Drive: no file id")
		val start = "https://drive.usercontent.google.com/download?id=$id&export=download"
		val p = fetch(start)
		if (FileHosts.loginWall(p)) return HostResult.NeedsLogin("Google Drive: the file is not shared publicly")
		if (p.contentType?.contains("html", true) != true) return HostResult.Direct(p.url)    // small file: the answer is the file
		if ("quota" in p.body.lowercase() && "exceeded" in p.body.lowercase()) return HostResult.Failed("Google Drive: download quota exceeded for this file")
		val form = Regex("""<form[^>]+id="download-form"[^>]+action="([^"]+)"[^>]*>(.*?)</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(p.body)
			?: return HostResult.Failed("Google Drive: no download form on the warning page")
		val action = FileHosts.unescape(form.groupValues[1])
		val fields = Regex("""<input[^>]+type="hidden"[^>]+name="([^"]+)"[^>]+value="([^"]*)"""").findAll(form.groupValues[2]).map { it.groupValues[1] to FileHosts.unescape(it.groupValues[2]) }.toList()
		val target = (action.toHttpUrlOrNull() ?: url.resolve(action) ?: return HostResult.Failed("Google Drive: bad form action")).newBuilder()
		fields.forEach { (k, v) -> target.setQueryParameter(k, v) }
		return HostResult.Direct(target.build().toString())
	}
}

/** MediaFire: the file page carries the real link (plain href, or base64 in data-scrambled-url). */
object MediaFire : FileHostResolver {
	override val name = "MediaFire"
	override fun matches(url: HttpUrl) = FileHosts.hostIs(url, "mediafire.com") && !url.host.startsWith("download")
	override suspend fun resolve(url: HttpUrl, fetch: suspend (String) -> Page): HostResult {
		if (url.encodedPath.startsWith("/folder/")) return HostResult.Failed("MediaFire folder: not one file")
		val p = fetch(url.toString())
		if (p.code == 404 || "file removed" in p.body.lowercase() || "invalid or deleted file" in p.body.lowercase()) return HostResult.Failed("MediaFire: file removed")
		Regex("""data-scrambled-url="([A-Za-z0-9+/=]+)"""").find(p.body)?.let { m ->
			val decoded = runCatching { String(Base64.getDecoder().decode(m.groupValues[1])) }.getOrNull()
			if (decoded != null && decoded.startsWith("http")) return HostResult.Direct(decoded)
		}
		Regex("""(?:id="downloadButton"|aria-label="Download file")[^>]*href="(https?://download[^"]+)"""").find(p.body)?.let { return HostResult.Direct(FileHosts.unescape(it.groupValues[1])) }
		Regex("""href="(https?://download\d*\.mediafire\.com/[^"]+)"""").find(p.body)?.let { return HostResult.Direct(FileHosts.unescape(it.groupValues[1])) }
		if (FileHosts.captcha(p.body)) return HostResult.NeedsLogin("MediaFire: captcha")
		return HostResult.Failed("MediaFire: no download link on the page")
	}
}

/** OneDrive personal share links through the public shares API (u!<base64url>); business/SharePoint links need a login. */
object OneDrive : FileHostResolver {
	override val name = "OneDrive"
	override fun matches(url: HttpUrl) = FileHosts.hostIs(url, "1drv.ms", "onedrive.live.com", "sharepoint.com")
	override suspend fun resolve(url: HttpUrl, fetch: suspend (String) -> Page): HostResult {
		if (FileHosts.hostIs(url, "sharepoint.com")) return HostResult.NeedsLogin("OneDrive for Business / SharePoint: needs the owner's sign-in")
		if (url.host.equals("onedrive.live.com", true) && url.encodedPath.startsWith("/download")) return HostResult.Direct(url.toString())
		val token = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toString().toByteArray())
		return HostResult.Direct("https://api.onedrive.com/v1.0/shares/$token/root/content")
	}
}
