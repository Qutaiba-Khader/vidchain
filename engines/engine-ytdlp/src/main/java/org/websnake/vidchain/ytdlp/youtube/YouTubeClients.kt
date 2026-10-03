package org.websnake.vidchain.ytdlp.youtube

import org.websnake.vidchain.engine.KeyValueStore

/**
 * Method C's client rotation: yt-dlp's YouTube extractor can pretend to be different YouTube apps
 * (`--extractor-args youtube:player_client=<name>`). Some get a bot check or 403s, some need PO tokens we do not have.
 * The rotation tries clients in order, starting with the last winner, skips clients known to need a PO token, and
 * forgets everything when yt-dlp itself changes (new build = new client behaviour).
 */
class YouTubeClients(
	private val store: KeyValueStore,
	private val engineHash: String,
	val order: List<String> = DEFAULT_ORDER,
) {
	private val winnerKey get() = "yt.client.winner.$engineHash"
	private fun poKey(c: String) = "yt.client.needs_po.$engineHash.$c"

	/** clients to try, the last winner first, PO-token clients left out */
	fun plan(): List<String> {
		val winner = store.get(winnerKey)?.takeIf { it in order }
		return (listOfNotNull(winner) + order).distinct().filter { store.get(poKey(it)) == null }
	}

	fun won(client: String) = store.put(winnerKey, client)
	fun needsPoToken(client: String) = store.put(poKey(client), "1")

	enum class Verdict { NEXT_CLIENT, NEEDS_PO_TOKEN, GIVE_UP }

	companion object {
		/** clients that do not need PO tokens today (yt-dlp wiki, PO Token Guide); adjustable per release */
		val DEFAULT_ORDER = listOf("android_vr", "web_safari", "web_embedded", "tv")

		fun args(client: String) = listOf("--extractor-args", "youtube:player_client=$client")

		/** what an error from one client means for the rotation */
		fun judge(error: String): Verdict {
			val e = error.lowercase()
			return when {
				"po token" in e || "po_token" in e || "pot" in e && "provider" in e -> Verdict.NEEDS_PO_TOKEN
				"not a bot" in e || "confirm you" in e || "http error 403" in e || "403" in e && "forbidden" in e ||
					"requested format is not available" in e || "no video formats" in e || "only images are available" in e ||
					"precondition" in e || "this video is unavailable" in e && "app" in e -> Verdict.NEXT_CLIENT
				"private video" in e || "has been removed" in e || "copyright" in e || "members-only" in e || "age" in e && "sign in" in e ||
					"not available in your country" in e || "unsupported url" in e -> Verdict.GIVE_UP
				else -> Verdict.NEXT_CLIENT
			}
		}
	}
}
