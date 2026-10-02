package app.ui.main.guides

import android.view.View
import app.core.bases.BaseActivity
import com.aio.R
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import java.lang.ref.WeakReference

/**
 * A dialog guide that provides instructions for downloading from Instagram.
 *
 * This class displays a tutorial dialog showing users how to download content
 * from Instagram. It handles the dialog lifecycle and user interactions.
 *
 * @param baseActivity The parent activity that will host this dialog
 */
class InstDownloadGuide(private val baseActivity: BaseActivity?) {
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to parent activity to prevent memory leaks
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	// Dialog builder for creating and managing the tutorial dialog
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	init {
		safeBaseActivityRef?.let { _ ->
			logger.d("Initializing InstDownloadGuide dialog")

			// Set up the dialog layout using Instagram-specific tutorial template
			dialogBuilder.setView(R.layout.dialog_instagram_tutorial_1)

			// Allow dialog to be dismissed by tapping outside
			dialogBuilder.setCancelable(true)

			// Set up click listener for the primary action button
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)
		} ?: logger.d("safeBaseActivityRef is null — cannot initialize dialog")
	}

	/**
	 * Handles click events for dialog buttons.
	 * Currently only handles the close/dismiss button.
	 *
	 * @param button The view that was clicked
	 */
	private fun setupClickEvents(button: View) {
		logger.d("Button clicked with id=${button.id}")
		when (button.id) {
			R.id.btn_dialog_positive_container -> {
				logger.d("Close button clicked in InstDownloadGuide")
				close()
			}
		}
	}

	/**
	 * Displays the Instagram download guide dialog.
	 * Only shows the dialog if it's not already visible.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing InstDownloadGuide dialog")
			dialogBuilder.show()
		} else {
			logger.d("InstDownloadGuide dialog already showing — skipping show()")
		}
	}

	/**
	 * Closes the Instagram download guide dialog.
	 * Only attempts to close if the dialog is currently showing.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("Closing InstDownloadGuide dialog")
			dialogBuilder.close()
		} else {
			logger.d("InstDownloadGuide dialog already closed — skipping close()")
		}
	}
}