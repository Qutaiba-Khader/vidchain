package org.websnake.vidchain.fallback.trace

/**
 * Removes secrets from anything that goes into a trace line: cookie and auth header values (also JSON-quoted and
 * comma-separated), bearer tokens, the userinfo and EVERY query / fragment / matrix parameter value of any URL (signed
 * CDN links carry their credentials under many names: X-Amz-Security-Token, hdnts, __token__, lsig, ...), and
 * secret-looking name=value pairs outside URLs. Applied to every string field of every event.
 */
object Redactor {
	private val COOKIE = Regex("""(?i)\b(cookie|set-cookie)["']?\s*[:=]\s*["']?[^\s;,="']+=[^\s;,"']*(?:[;,]\s*[^\s;,="']+(?:=[^\s;,"']*)?)*""")
	private val AUTH = Regex("""(?i)\b(authorization|proxy-authorization|x-auth-token|x-api-key)["']?\s*[:=]\s*["']?(?:(?:bearer|basic)\s+)?[^\s"',}]+""")
	private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
	private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]+""")
	private val USERINFO = Regex("""(?i)^([a-z][a-z0-9+.-]*://)[^/?#@]+@""")
	private val PARAM = Regex("""([?&;#][^=&;#]*=)[^&;#]+""")
	private val NAMED = Regex("""(?i)\b([a-z0-9_.-]*(?:token|sig|signature|key|secret|passw|pwd|auth|session|credential|policy|hmac|jwt|hdnts|hdnea|sid)[a-z0-9_.-]*\s*=\s*)(?!<redacted>)[^&\s"';,]+""")
	/** the same parameters inside a percent-encoded link (?, &, #, ; and = written as %3F, %26, %23, %3B, %3D) */
	private val ENCODED = Regex("""(?i)((?:%3F|%26|%23|%3B)(?:(?!%3D|%26|%23|%3B)[^\s&;#"'])*%3D)(?:(?!%26|%23|%3B)[^\s&;#"'])+""")
	private val CONTROL = Regex("""[\r\n\t]+""")

	fun clean(s: String?): String? = s?.let {
		var t = COOKIE.replace(it) { m -> "${m.groupValues[1]}: <redacted>" }
		t = AUTH.replace(t) { m -> "${m.groupValues[1]}: <redacted>" }
		t = BEARER.replace(t) { m -> "${m.groupValues[1]} <redacted>" }
		t = URL.replace(t) { m ->
			val noUser = USERINFO.replace(m.value) { u -> "${u.groupValues[1]}<redacted>@" }
			PARAM.replace(noUser) { p -> "${p.groupValues[1]}<redacted>" }
		}
		t = ENCODED.replace(t) { m -> "${m.groupValues[1]}<redacted>" }
		t = NAMED.replace(t) { m -> "${m.groupValues[1]}<redacted>" }
		CONTROL.replace(t, " ").take(MAX_FIELD)
	}

	private const val MAX_FIELD = 300
}
