package org.websnake.vidchain.fallback.fixes

import kotlin.coroutines.cancellation.CancellationException

/**
 * T9 stage 1 (the 7 carried issues, decision 8): pure rules of small fixes in the original app, JVM-tested
 * (SmallFixesTest). Each call site is marked `T9` in the code and listed in BUILD-FIXES.md.
 */

/** issue 5: a host belongs to a site only when it IS the site's domain or a subdomain of it ("dropbox.com" is not "x.com") */
object HostMatch {
	fun isHost(host: String?, domain: String): Boolean {
		val h = host?.lowercase()?.trimEnd('.') ?: return false
		val d = domain.lowercase()
		return h == d || h.endsWith(".$d")
	}

	fun isAnyHost(host: String?, vararg domains: String): Boolean = domains.any { isHost(host, it) }
}

/** issue 1: the app keeps its read timeout in milliseconds; yt-dlp's --socket-timeout takes seconds */
object YtDlpTimeout {
	fun seconds(ms: Int): Int = ((ms + 999) / 1000).coerceIn(5, 300)
}

/** issue 7: a retry loop that does not start the work again after the caller stopped it (a killed process) */
object StoppableRetry {
	fun <T> run(limit: Int, stopped: () -> Boolean, empty: (T) -> Boolean, attempt: () -> T): T {
		var result = attempt()
		var tries = 1
		while (empty(result) && tries < limit && !stopped()) {
			result = attempt()
			tries++
		}
		return result
	}
}

/** issue 2: a broad catch must not swallow a coroutine's cancellation */
object CancelAware {
	fun rethrowCancel(e: Throwable) {
		if (e is CancellationException) throw e
	}
}
