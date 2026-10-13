package org.websnake.vidchain.app

import android.os.Handler
import android.os.Looper
import lib.ui.builders.WaitingDialog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inherited bug fix (T8.13, owner report: a shared X link stayed on "Analyzing URL, Please Wait..." for minutes).
 * The app's waits run blocking network work inside a coroutine `withTimeout`, which only acts at a suspension point,
 * so its 30 s limit never ended a wait: a site that does not answer kept the dialog up for about 6 minutes (11 page
 * fetches of 30 s each, measured on an Android 11 phone). A [Deadline] ends the wait on the main thread at a fixed time,
 * whatever the work is doing; whoever ends it first (result, error, Cancel or the deadline) wins and the others are
 * ignored. New file inside the upstream source tree; used only from the lines marked T8.13.
 */
object VidChainWaits {

	/** how long an "Analyzing URL" wait may last (the app's own 30 s job limit, plus a margin for a slow phone) */
	const val ANALYSIS_MS = 40_000L

	/**
	 * the same for a social link (X, Instagram, ...): the app reads the whole page first, and x.com's page alone took
	 * 6-15 s from a fast line on 2026-10-13 (29-33 s for the whole analysis on the test phone), so it gets longer
	 */
	const val SOCIAL_MS = 60_000L

	/** how long the resolution picker waits for the page title before it makes one up (Download needs a title); one page fetch may take 30 s */
	const val TITLE_MS = 35_000L

	/** a file move (copy + delete) may take minutes for a big video on a slow card; the dialog closes when it ends */
	const val MOVE_FILE_MS = 60 * 60_000L

	/** a wait that ends exactly once */
	class Deadline(private val ms: Long = ANALYSIS_MS, private val onExpired: () -> Unit) {
		private val settled = AtomicBoolean(false)
		private val main = Handler(Looper.getMainLooper())
		private val expire = Runnable { if (settled.compareAndSet(false, true)) runCatching(onExpired) }

		fun start(): Deadline = apply { main.postDelayed(expire, ms) }

		/** true only for the first caller: that caller ends the wait, every later one must do nothing */
		fun settle(): Boolean = settled.compareAndSet(false, true).also { if (it) main.removeCallbacks(expire) }

		/** the work failed: end the wait now the way the deadline would (on the main thread) */
		fun expireNow() { main.removeCallbacks(expire); main.post(expire) }

		/** the wait already ended: the background work should stop at its next step and fetch nothing more */
		val isSettled: Boolean get() = settled.get()
	}

	/**
	 * A Cancel button that really cancels. The dialog's own button calls DialogBuilder.close(), which removes the
	 * cancel listener before dismissing, so a listener given to WaitingDialog never ran (T8.13 review).
	 */
	@JvmStatic
	fun onCancel(dialog: WaitingDialog, action: () -> Unit) {
		dialog.dialogBuilder?.setOnClickForPositiveButton {
			runCatching(action)
			dialog.close()
		}
	}
}
