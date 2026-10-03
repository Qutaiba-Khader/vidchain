package org.websnake.vidchain.fallback.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.CLOSE
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.COMPLETE
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.DOWNLOADING

/**
 * One test per truth-table row (fallback-core/CLASSIFIER.md) and one per fault-matrix scenario
 * (ci/golden/FAULT-MATRIX.md), each built from the fields the upstream code writes in that situation
 * (file:line in the comments, upstream master).
 */
class TerminalClassifierTest {

	private fun regular(status: Int, block: DownloadSnapshot.() -> DownloadSnapshot = { this }) =
		DownloadSnapshot(engine = Engine.REGULAR, status = status).block()

	private fun m3u8(status: Int, block: DownloadSnapshot.() -> DownloadSnapshot = { this }) =
		DownloadSnapshot(engine = Engine.M3U8, status = status).block()

	private fun failure(s: DownloadSnapshot) = (TerminalClassifier.classify(s) as Verdict.Failure).cls

	// --- truth table rows -------------------------------------------------------------------------------

	@Test fun `row1 deleted or cleared by the user never falls back`() {
		val v = TerminalClassifier.classify(regular(CLOSE) { copy(isDeleted = true) })   // DownloadSystem.kt:511
		assertEquals(Verdict.UserStopped(UserIntent.DELETE), v)
		assertFalse(v.fallbackEligible)
		assertEquals(Verdict.UserStopped(UserIntent.CLEAR),
			TerminalClassifier.classify(regular(CLOSE) { copy(isRemoved = true, userIntent = UserIntent.CLEAR) }))
	}

	@Test fun `row2 completed is success`() {                                          // RegularDownloader.kt:293-296
		assertEquals(Verdict.Success, TerminalClassifier.classify(regular(COMPLETE) { copy(isComplete = true, statusKey = StatusKey.COMPLETED) }))
		assertEquals(Verdict.Success, TerminalClassifier.classify(m3u8(CLOSE) { copy(isComplete = true) }))
	}

	@Test fun `row3 waiting for the network is not a failure`() {                      // RegularDownloader.kt:874-895
		val v = TerminalClassifier.classify(regular(DOWNLOADING) { copy(isRunning = true, isWaitingForNetwork = true, statusKey = StatusKey.WAITING_WIFI) })
		assertEquals(Verdict.Waiting(StatusKey.WAITING_WIFI), v)
		assertFalse(v.fallbackEligible)
	}

	@Test fun `row4 downloading with recent progress is in progress, without progress it stalled`() {
		assertEquals(Verdict.InProgress, TerminalClassifier.classify(regular(DOWNLOADING) { copy(isRunning = true, msSinceLastProgress = 5_000) }))
		assertEquals(Verdict.InProgress, TerminalClassifier.classify(regular(DOWNLOADING) { copy(isRunning = true) }))
		assertEquals(FailureClass.STALLED,                                                // RegularDownloader.kt:866-868
			failure(regular(DOWNLOADING) { copy(isRunning = true, msSinceLastProgress = TerminalClassifier.STALL_AFTER_MS) }))
	}

	@Test fun `row5 a recorded user pause never falls back`() {                        // RegularDownloader.kt:181-182
		for (i in listOf(UserIntent.PAUSE, UserIntent.RENAME_PAUSE, UserIntent.APP_SHUTDOWN)) {
			val v = TerminalClassifier.classify(regular(CLOSE) { copy(statusKey = StatusKey.PAUSED, userIntent = i) })
			assertEquals(Verdict.UserStopped(i), v)
			assertFalse(v.fallbackEligible)
		}
	}

	@Test fun `row6 storage problems are failures that another method cannot fix`() {
		for (s in listOf(
			regular(CLOSE) { copy(isFailedToAccessFile = true, statusKey = StatusKey.FILE_IO_FAILED) },          // :738-741
			regular(CLOSE) { copy(hasStorageDialogMessage = true) },                                         // :786
			regular(CLOSE) { copy(isDestinationFileNotExisted = true, statusKey = StatusKey.FILE_DELETED) },  // :414-416, :822
			// storage error surfacing as "expired" (FileNotFoundException from RandomAccessFile): storage wins
			regular(CLOSE) { copy(isFileUrlExpired = true, isDestinationFileNotExisted = true, statusKey = StatusKey.LINK_EXPIRED) },
		)) {
			val v = TerminalClassifier.classify(s)
			assertEquals(Verdict.Failure(FailureClass.STORAGE), v)
			assertFalse(v.fallbackEligible)
		}
	}

	@Test fun `row7 extractor problems keep their reason`() {                          // M3U8VideoDownloader.kt:1479-1527
		val y = { k: StatusKey -> m3u8(CLOSE) { copy(isYtdlpHavingProblem = true, ytdlpProblem = k, statusKey = k) } }
		assertEquals(FailureClass.EXTRACTOR_LOGIN, failure(y(StatusKey.LOGIN_REQUIRED)))
		assertEquals(FailureClass.EXTRACTOR_UNAVAILABLE, failure(y(StatusKey.CONTENT_NOT_AVAILABLE)))
		assertEquals(FailureClass.EXTRACTOR_UNAVAILABLE, failure(y(StatusKey.SITE_BANNED)))
		assertEquals(FailureClass.EXTRACTOR_FORMAT, failure(y(StatusKey.FORMAT_NOT_FOUND)))
		assertEquals(FailureClass.HTTP_OR_SERVER, failure(y(StatusKey.SERVER_PROBLEM)))
		assertEquals(FailureClass.EXTRACTOR_OTHER, failure(y(StatusKey.OTHER)))
		assertTrue(TerminalClassifier.classify(y(StatusKey.LOGIN_REQUIRED)).fallbackEligible)
	}

	@Test fun `row8 expired link`() {                                                  // M3U8VideoDownloader.kt:1452, Regular :214-216
		assertEquals(FailureClass.EXPIRED_URL, failure(m3u8(CLOSE) { copy(isFileUrlExpired = true) }))
		assertEquals(FailureClass.EXPIRED_URL, failure(regular(CLOSE) { copy(statusKey = StatusKey.LINK_EXPIRED) }))
	}

	@Test fun `row9 remaining status texts`() {
		assertEquals(FailureClass.INVALID_URL, failure(regular(CLOSE) { copy(statusKey = StatusKey.INVALID_URL) }))      // :519-520
		assertEquals(FailureClass.HTTP_OR_SERVER, failure(regular(CLOSE) { copy(statusKey = StatusKey.DOWNLOAD_FAILED) })) // :147-155
		assertEquals(FailureClass.HTTP_OR_SERVER, failure(m3u8(CLOSE) { copy(statusKey = StatusKey.SERVER_ISSUE) }))
	}

	@Test fun `row9 a pause nobody asked for is an error pause`() {                    // M3U8VideoDownloader.kt:1336-1337
		val v = TerminalClassifier.classify(m3u8(CLOSE) { copy(statusKey = StatusKey.PAUSED) })
		assertEquals(Verdict.Failure(FailureClass.CRASH_OR_UNKNOWN), v)
		assertTrue(v.fallbackEligible)
		assertEquals(FailureClass.CRASH_OR_UNKNOWN, failure(regular(CLOSE)))                // DownloadSystem.kt:727 (no text)
	}

	// --- fault matrix (ci/golden/FAULT-MATRIX.md): the state the upstream code leaves behind ------------------

	@Test fun `fault 403 or 404 or 500 or 429 after the sniff - Regular retries run out and the model stays DOWNLOADING`() {
		val left = regular(DOWNLOADING) { copy(isRunning = true, msSinceLastProgress = 300_000) }   // RegularDownloader.kt:866-868
		assertEquals(FailureClass.STALLED, failure(left))
	}

	@Test fun `fault reset or hang after the sniff - no progress, same as above`() {
		assertEquals(FailureClass.STALLED, failure(regular(DOWNLOADING) { copy(isRunning = true, msSinceLastProgress = 600_000) }))
	}

	@Test fun `fault html error page after the sniff - upstream marks it complete (badge only, Q26)`() {
		assertEquals(Verdict.Success, TerminalClassifier.classify(regular(COMPLETE) { copy(isComplete = true, statusKey = StatusKey.COMPLETED) }))
	}

	@Test fun `fault HLS segment 404 - M3U8 exception pause plus crash log`() {             // :1336-1337
		assertEquals(FailureClass.CRASH_OR_UNKNOWN, failure(m3u8(CLOSE) { copy(statusKey = StatusKey.PAUSED) }))
	}

	@Test fun `fault M3U8 offline - upstream calls it expired, still eligible`() {          // URLUtilityKT.kt:507-520
		val v = TerminalClassifier.classify(m3u8(CLOSE) { copy(isFileUrlExpired = true, statusKey = StatusKey.LINK_EXPIRED) })
		assertEquals(Verdict.Failure(FailureClass.EXPIRED_URL), v)
		assertTrue(v.fallbackEligible)
	}

	@Test fun `the same error pause with a recorded user pause is the user's`() {
		val s = m3u8(CLOSE) { copy(statusKey = StatusKey.PAUSED, userIntent = UserIntent.PAUSE) }
		assertEquals(Verdict.UserStopped(UserIntent.PAUSE), TerminalClassifier.classify(s))
	}

	@Test fun `every status key and every intent gives exactly one verdict (no exception, no gap)`() {
		var n = 0
		for (e in Engine.values()) for (st in listOf(DOWNLOADING, CLOSE, COMPLETE, 0)) for (k in StatusKey.values())
			for (i in UserIntent.values()) for (flags in 0 until 64) {
				val s = DownloadSnapshot(engine = e, status = st, statusKey = k, userIntent = i,
					isDeleted = flags and 1 != 0, isFileUrlExpired = flags and 2 != 0, isYtdlpHavingProblem = flags and 4 != 0,
					isWaitingForNetwork = flags and 8 != 0, isFailedToAccessFile = flags and 16 != 0, isComplete = flags and 32 != 0,
					ytdlpProblem = k, msSinceLastProgress = if (flags % 3 == 0) null else flags * 10_000L)
				TerminalClassifier.classify(s); n++
			}
		assertEquals(Engine.values().size * 4 * StatusKey.values().size * UserIntent.values().size * 64, n)
	}
}
