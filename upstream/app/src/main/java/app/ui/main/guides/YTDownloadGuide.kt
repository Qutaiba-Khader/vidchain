package app.ui.main.guides

import android.view.View
import app.core.bases.BaseActivity
import com.aio.R
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import java.lang.ref.WeakReference

/**
 * A dialog guide that provides step-by-step instructions for downloading from YouTube.
 *
 * This class creates and manages a tutorial dialog that walks users through
 * the process of downloading YouTube content with the AIO App. It safely references
 * the hosting activity, handles dialog lifecycle events, and manages user interactions.
 *
 * @param baseActivity The parent activity hosting this guide dialog.
 */
class YTDownloadGuide(private val baseActivity: BaseActivity?) {
	private val logger = LogHelperUtils.from(javaClass)

	// Holds a safe reference to the parent activity to prevent memory leaks.
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	// Builds and manages the tutorial dialog.
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	init {
		safeBaseActivityRef?.let {
			logger.d("Initializing YTDownloadGuide dialog")

			// Configure dialog layout and properties.
			dialogBuilder.setView(R.layout.dialog_youtube_tutorial_1)
			dialogBuilder.setCancelable(true)

			// Attach click listeners to dialog buttons.
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)
		} ?: logger.d("safeBaseActivityRef is null — cannot initialize YTDownloadGuide dialog")
	}

	/**
	 * Handles click events from dialog buttons.
	 *
	 * @param button The button view that was clicked.
	 */
	private fun setupClickEvents(button: View) {
		logger.d("Button clicked with id=${button.id}")
		when (button.id) {
			R.id.btn_dialog_positive_container -> {
				logger.d("Close button clicked in YTDownloadGuide")
				close()
			}
		}
	}

	/**
	 * Displays the YouTube download guide dialog if it is not already visible.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing YTDownloadGuide dialog")
			dialogBuilder.show()
		} else {
			logger.d("YTDownloadGuide dialog already showing — skipping show()")
		}
	}

	/**
	 * Closes the YouTube download guide dialog if it is currently visible.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("Closing YTDownloadGuide dialog")
			dialogBuilder.close()
		} else {
			logger.d("YTDownloadGuide dialog already closed — skipping close()")
		}
	}
}
