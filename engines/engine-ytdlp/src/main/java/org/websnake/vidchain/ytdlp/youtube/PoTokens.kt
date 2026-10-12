package org.websnake.vidchain.ytdlp.youtube

/**
 * YouTube proof-of-origin tokens for one video (T8.12, owner decision): [gvs] is bound to [visitorData] (the
 * logged-out visitor id), [player] to the video id. Minted on the phone by a hidden WebView running BotGuard
 * (engine-web PoTokenMinter); handed to yt-dlp so the "web"/"mweb" clients, which YouTube now asks for these tokens,
 * can be tried too. Pure, JVM-tested.
 *
 * They are tokens: never logged, never in a trace line (only "minted" / "not minted").
 */
class PoTokens private constructor(val visitorData: String, val gvs: String, val player: String) {
	override fun toString() = "PoTokens(<redacted>)"

	companion object {
		/** base64url (BotGuard writes websafe base64; '=' padding allowed) */
		private val TOKEN = Regex("""^[A-Za-z0-9_-]{20,2000}={0,2}$""")
		/** visitor data as YouTube hands it out: base64, often percent-encoded */
		private val VISITOR = Regex("""^[A-Za-z0-9_%=-]{10,2000}$""")
		private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

		/**
		 * Null unless every part is plain base64. The values go into one yt-dlp extractor argument where ';' ',' '+'
		 * and ':' are separators, so anything else (a page could post any string) must never get through.
		 */
		fun of(visitorData: String?, gvs: String?, player: String?): PoTokens? {
			if (visitorData == null || gvs == null || player == null) return null
			if (!VISITOR.matches(visitorData) || !TOKEN.matches(gvs) || !TOKEN.matches(player)) return null
			return PoTokens(visitorData, gvs, player)
		}

		fun isVideoId(id: String?) = id != null && VIDEO_ID.matches(id)

		/** the 11-character video id from watch?v=, youtu.be/, /shorts/, /live/, /embed/ or /v/ links; null otherwise */
		fun videoId(url: String): String? {
			val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
			val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("music.") ?: return null
			val path = u.rawPath.orEmpty()
			val id = when {
				host == "youtu.be" -> path.trim('/').substringBefore('/')
				host == "youtube.com" || host == "youtube-nocookie.com" -> when {
					path == "/watch" -> u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
					else -> Regex("""^/(?:shorts|live|embed|v)/([^/?#]+)""").find(path)?.groupValues?.get(1)
				}
				else -> null
			}
			return id?.takeIf { isVideoId(it) }
		}
	}
}
