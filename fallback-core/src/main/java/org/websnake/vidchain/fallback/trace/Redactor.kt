package org.websnake.vidchain.fallback.trace

/**
 * Removes secrets from anything that goes into a trace line: cookie and auth header values, bearer tokens,
 * and secret-looking URL query parameters. Applied to every string field of every event.
 */
object Redactor {
	private val COOKIE = Regex("""(?i)\b(cookie|set-cookie)\s*[:=]\s*[^\s;,=]+=[^\s;,]*(?:;\s*[^\s;,=]+(?:=[^\s;,]*)?)*""")
	private val AUTH = Regex("""(?i)\b(authorization|proxy-authorization|x-auth-token|x-api-key)\s*[:=]\s*(?:(?:bearer|basic)\s+)?\S+""")
	private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
	private val QUERY = Regex("""(?i)([?&](?:token|access_token|refresh_token|id_token|sig|signature|key|api_key|apikey|auth|session|sessionid|sid|password|pwd|secret|policy|key-pair-id|x-amz-signature|x-amz-credential|x-goog-signature)=)[^&#\s"']*""")
	private val CONTROL = Regex("""[\r\n\t]+""")

	fun clean(s: String?): String? = s?.let {
		var t = COOKIE.replace(it) { m -> "${m.groupValues[1]}: <redacted>" }
		t = AUTH.replace(t) { m -> "${m.groupValues[1]}: <redacted>" }
		t = BEARER.replace(t) { m -> "${m.groupValues[1]} <redacted>" }
		t = QUERY.replace(t) { m -> "${m.groupValues[1]}<redacted>" }
		CONTROL.replace(t, " ").take(MAX_FIELD)
	}

	private const val MAX_FIELD = 300
}
