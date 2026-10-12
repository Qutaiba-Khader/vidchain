package org.websnake.vidchain.web.refetch

import java.net.URI
import java.util.Locale

/**
 * What the re-fetcher may touch, and what it does with an answer (T8.12, owner decision; Web Video Caster re-fetches
 * a page's requests itself to see what they really are). Pure, JVM-tested. Every decision is taken BEFORE a byte is
 * sent; only a clean 2xx is ever handed to the page, anything else goes back to the WebView untouched.
 */
object RefetchPolicy {

	/** a request as shouldInterceptRequest shows it */
	data class Facts(val url: String, val method: String?, val mainFrame: Boolean, val headers: Map<String, String>)

	sealed class Verdict {
		data class Skip(val why: String) : Verdict()
		/** [disguised]: the name of the disguise rule that made a static-looking address worth a look */
		data class Fetch(val disguised: String?) : Verdict()
	}

	fun decide(f: Facts, gate: PageGate, rules: DisguiseRules, never: NeverList): Verdict {
		if (f.mainFrame) return Verdict.Skip("main frame")
		if (!f.method.equals("GET", ignoreCase = true)) return Verdict.Skip("not GET")
		val url = f.url
		if (url.length > 4_000 || !(url.startsWith("https://", true) || url.startsWith("http://", true))) return Verdict.Skip("scheme")
		val h = f.headers.mapKeys { it.key.lowercase(Locale.ROOT) }
		if ("range" in h || "upgrade" in h) return Verdict.Skip("range / upgrade")
		val accept = h["accept"].orEmpty().lowercase(Locale.ROOT)
		if (accept.startsWith("image/") || accept.startsWith("text/css") || accept.startsWith("font/") || "text/event-stream" in accept ||
			accept.startsWith("video/") || accept.startsWith("audio/")) return Verdict.Skip("accept")
		if (oneTime(url)) return Verdict.Skip("one-time link")
		val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return Verdict.Skip("no host")
		if (QUIET_HOSTS.any { host == it || host.endsWith(".$it") }) return Verdict.Skip("fonts / analytics")
		if (MEDIA.containsMatchIn(url.substringBefore('?').substringBefore('#'))) return Verdict.Skip("media address")   // the grabber already lists it
		if ("/videoplayback" in url && "googlevideo" in host) return Verdict.Skip("media address")
		val disguised = if (rules.staticExtension(url) != null) rules.match(url) ?: return Verdict.Skip("static") else null
		if (never.has(url)) return Verdict.Skip("handed back before")
		if (!gate.take()) return Verdict.Skip("page budget")
		return Verdict.Fetch(disguised)
	}

	/**
	 * Signed or one-time links: a second request could spend the token the page needs, so they are never re-fetched.
	 * Query keys only (values are never looked at for anything else).
	 */
	fun oneTime(url: String): Boolean {
		val q = url.substringAfter('?', "").substringBefore('#')
		if (q.isEmpty()) return false
		return q.split('&').any { p -> ONE_TIME.matches(p.substringBefore('=').lowercase(Locale.ROOT)) }
	}

	/** what the first bytes and the Content-Type say; the probe's tags, so the grabber and method W read them alike */
	fun sniff(contentType: String?, head: ByteArray): String? {
		val text = String(head, 0, minOf(head.size, 1024), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\n', '\r', '\t')
		if (text.startsWith("#EXTM3U")) return "body-hls"
		if (text.startsWith("<") && Regex("""<MPD[\s>]""").containsMatchIn(text)) return "body-dash"
		if (head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") return "ct"
		val ct = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
		return when {
			"mpegurl" in ct -> "ct-hls"
			ct == "application/dash+xml" || ct == "video/vnd.mpeg.dash.mpd" -> "ct-dash"
			ct == "video/mp2t" -> null                                          // a stream piece
			ct.startsWith("video/") || ct.startsWith("audio/") -> "ct"
			else -> null
		}
	}

	/** a 2xx that is a bot check, not the page's answer: given back so the WebView asks for itself */
	fun challenge(head: ByteArray): Boolean {
		val t = String(head, 0, minOf(head.size, 2048), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
		return CHALLENGE.any { it in t }
	}

	private val ONE_TIME = Regex("""(?:x-amz-signature|x-amz-security-token|signature|sig|hmac|token|.*_token|hdnts|hdnea|__token__|policy|key-pair-id|expires|exp|e|st|nonce|otp|ticket)""")
	private val MEDIA = Regex("""\.(?:mp4|m4v|m4a|mkv|webm|mov|avi|flv|wmv|3gp|ts|m4s|cmfv|cmfa|aac|mp3|ogg|oga|opus|wav|flac|m3u8|mpd)$""", RegexOption.IGNORE_CASE)
	private val QUIET_HOSTS = listOf("fonts.googleapis.com", "fonts.gstatic.com", "google-analytics.com", "googletagmanager.com",
		"doubleclick.net", "googlesyndication.com", "facebook.net", "hotjar.com", "clarity.ms")
	private val CHALLENGE = listOf("cf-chl", "challenge-platform", "captcha", "are you a robot", "ddos-guard", "just a moment")

	/**
	 * One page's allowance: re-fetching starts only when [armed] (the visible browser arms it when the page shows a
	 * video; the hidden catcher from the start), at most [budget] requests, and three hand-backs end it for the page.
	 */
	class PageGate(private val budget: Int, @Volatile var armed: Boolean) {
		private var used = 0
		private var handedBack = 0
		private var found = 0
		@Synchronized fun take(): Boolean { if (!armed || handedBack >= MAX_HANDBACKS || used >= budget) return false; used++; return true }
		@Synchronized fun handedBack() { handedBack++ }
		@Synchronized fun found() { found++ }
		@Synchronized fun stopped() = handedBack >= MAX_HANDBACKS
		/** counts only, for the trace log (never an address) */
		@Synchronized fun summary(): String? = if (used == 0) null else "re-fetched=$used handed-back=$handedBack found=$found"
		private companion object { const val MAX_HANDBACKS = 3 }
	}

	/** address shapes given back once: the WebView fetches them itself for the rest of the session */
	class NeverList(private val max: Int = 2_000) {
		private val shapes = LinkedHashSet<String>()
		@Synchronized fun has(url: String) = shapeOf(url)?.let { it in shapes } ?: false
		@Synchronized fun add(url: String) { shapeOf(url)?.let { shapes += it; while (shapes.size > max) shapes.remove(shapes.first()) } }
		private fun shapeOf(url: String) = DisguiseRules.shape(url)
	}
}
