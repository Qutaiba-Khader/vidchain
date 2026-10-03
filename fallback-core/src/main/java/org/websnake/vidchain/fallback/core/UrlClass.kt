package org.websnake.vidchain.fallback.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** The six URL classes of the plan, each with its own fallback chain (B1..B6). */
enum class UrlClass(val chain: String) {
	YOUTUBE("B1"), LISTED_SITE("B2"), UNLISTED_PAGE("B3"), DIRECT_FILE("B4"), HLS_DASH("B5"), MAGNET_TORRENT("B6");

	companion object {
		/** the upstream allow-list (SupportedURLs.kt:48), domain names without TLD */
		private val LISTED = setOf("youtube", "youtu", "facebook", "instagram", "twitter", "x", "tiktok", "reddit", "tumblr",
			"soundcloud", "bandcamp", "9gag", "vk", "imdb", "dailymotion", "bilibili", "twitch", "likee", "vimeo", "snapchat",
			"pinterest", "linkedin", "mixcloud", "audiomack", "periscope", "jiosaavn", "hotstar", "youku", "rumble", "odysee",
			"peertube", "bitchute", "liveleak")
		private val FILE_EXT = Regex("""\.(mp4|m4v|mkv|webm|mov|avi|flv|3gp|ts|mp3|m4a|aac|ogg|opus|flac|wav|zip|rar|7z|apk|pdf)$""")
		private val FILE_HOSTS = setOf("drive.google.com", "docs.google.com", "dropbox.com", "www.dropbox.com", "dl.dropboxusercontent.com",
			"mediafire.com", "www.mediafire.com", "pixeldrain.com", "1drv.ms", "onedrive.live.com")

		fun of(url: String): UrlClass {
			val u = url.trim()
			val lower = u.lowercase(Locale.ROOT)
			if (lower.startsWith("magnet:") || lower.substringBefore('#').substringBefore('?').endsWith(".torrent")) return MAGNET_TORRENT
			// OkHttp's parser for web links (it accepts what servers accept: spaces, '|'), java.net.URI for the rest
			val web = u.toHttpUrlOrNull()
			val host = (web?.host ?: runCatching { java.net.URI(u).host }.getOrNull())?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: ""
			val path = (web?.encodedPath?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
				?: runCatching { java.net.URI(u).path }.getOrNull())?.lowercase(Locale.ROOT) ?: ""
			if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return HLS_DASH
			if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) return YOUTUBE
			if (host in FILE_HOSTS || FILE_EXT.containsMatchIn(path)) return DIRECT_FILE
			val labels = host.split('.')
			if (labels.size >= 2 && labels[labels.size - 2] in LISTED) return LISTED_SITE
			return UNLISTED_PAGE
		}
	}
}
