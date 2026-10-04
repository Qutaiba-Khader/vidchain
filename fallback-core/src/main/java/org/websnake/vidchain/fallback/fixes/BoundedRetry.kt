package org.websnake.vidchain.fallback.fixes

/**
 * T8.7 a/c (inherited bug fix, owner INBOX #8): the original app fetched a page in a loop that never ended while
 * the page kept failing (no sleep, a new HTTP client per pass): thousands of requests a minute on a 404 and, on an
 * HLS master playlist that 404s, an OutOfMemoryError crash loop. This is the rule the fixed fetch follows.
 */
object BoundedRetry {
	const val MAX_ATTEMPTS = 4

	/** an HTTP status that will not change on a retry (4xx except 408 timeout / 429 rate limit) */
	fun permanent(code: Int): Boolean = code in 400..499 && code != 408 && code != 429

	/** wait before attempt [attempt] + 1 (0-based): 0.5 s, 1 s, 2 s, then 4 s at most */
	fun backoffMs(attempt: Int): Long = minOf(500L shl attempt.coerceIn(0, 4), 4_000L)

	/** attempts the caller may make for a requested retry count: at least 1, at most [MAX_ATTEMPTS] */
	fun attempts(requested: Int): Int = requested.coerceIn(1, MAX_ATTEMPTS)

	/**
	 * Runs [fetch] up to [attempts] times. [fetch] returns the body (null = failed) and the HTTP status (0 = no
	 * answer). Stops at the first body or the first permanent status; sleeps [backoffMs] between attempts.
	 */
	fun <T : Any> run(attempts: Int, sleep: (Long) -> Unit = Thread::sleep, fetch: (attempt: Int) -> Pair<T?, Int>): T? {
		val n = attempts.coerceIn(1, MAX_ATTEMPTS)
		for (attempt in 0 until n) {
			val (body, code) = fetch(attempt)
			if (body != null) return body
			if (permanent(code)) return null
			if (attempt < n - 1) sleep(backoffMs(attempt))
		}
		return null
	}
}

/** T8.7 b: the server's answer when it refused a download request (the original app threw the status code away) */
class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")
