package org.websnake.vidchain.fallback.context

import java.net.URI

/** Shared text such as "Look at this! https://site.example/v/1 #fun" -> the link inside it. */
object ShareText {
	private val URL_IN_TEXT = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

	/** the first http(s) link inside [text]; null when there is none, or when the text already is exactly that link */
	fun firstUrl(text: String?): String? {
		val t = text?.trim() ?: return null
		val u = URL_IN_TEXT.find(t)?.value?.trimEnd('.', ',', ')', ']', '}', '!', '?', ';', ':', '"', '\'') ?: return null
		return u.takeIf { it != t && runCatching { URI(it).host }.getOrNull() != null }
	}
}
