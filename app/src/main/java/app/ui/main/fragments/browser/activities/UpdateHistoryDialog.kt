package app.ui.main.fragments.browser.activities

import android.view.View
import android.widget.EditText
import app.core.AIOApp.Companion.aioBookmark
import app.core.engines.browser.history.HistoryModel
import com.aio.R
import lib.networks.URLUtility.isValidURL
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * Dialog for updating an existing browser history entry (title & URL).
 *
 * This class shows a dialog with editable fields, validates input,
 * and applies changes to the given [HistoryModel].
 */
class UpdateHistoryDialog(
	private val historyActivity: HistoryActivity,
	private val onApply: (Boolean) -> Unit = {}
) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the activity */
	private val safeHistoryActivity = WeakReference(historyActivity).get()
	private var historyModel: HistoryModel? = null

	/** Lazily created dialog with input fields for editing history title & URL */
	private val dialog by lazy {
		DialogBuilder(safeHistoryActivity).apply {
			setView(R.layout.dialog_browser_history_edit_1)
			setCancelable(true)

			view.apply {
				val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
				val editFieldName = findViewById<EditText>(R.id.edit_field_name)

				// Request focus for URL input field
				findViewById<View>(R.id.container_edit_field_url).setOnClickListener {
					logger.d("URL field container clicked, requesting focus.")
					requestFocusOnEditTextField(editFieldUrl)
				}

				// Request focus for Name input field
				findViewById<View>(R.id.container_edit_field_name).setOnClickListener {
					logger.d("Name field container clicked, requesting focus.")
					requestFocusOnEditTextField(editFieldName)
				}

				// Save / Apply button
				findViewById<View>(R.id.btn_dialog_positive_container).setOnClickListener {
					logger.d("Apply button clicked, validating inputs.")

					val enteredHistoryName = editFieldName.text.toString()
					if (enteredHistoryName.isEmpty()) {
						logger.d("Validation failed: history title is empty.")
						safeHistoryActivity?.doSomeVibration(50)
						showToast(
							activityInf = safeHistoryActivity,
							msgId = R.string.title_bookmark_name_can_not_be_empty
						)
						return@setOnClickListener
					}

					val enteredHistoryUrl = editFieldUrl.text.toString()
					if (enteredHistoryUrl.isEmpty()) {
						logger.d("Validation failed: history URL is empty.")
						safeHistoryActivity?.doSomeVibration(50)
						showToast(
							activityInf = safeHistoryActivity,
							msgId = R.string.title_bookmark_url_can_not_be_empty
						)
						return@setOnClickListener
					}

					if (!isValidURL(enteredHistoryUrl)) {
						logger.d("Validation failed: invalid history URL = $enteredHistoryUrl")
						safeHistoryActivity?.doSomeVibration(50)
						showToast(
							activityInf = safeHistoryActivity,
							msgId = R.string.title_bookmark_url_not_valid
						)
						return@setOnClickListener
					}

					// Apply changes to the history model
					logger.d("Updating history entry: title='$enteredHistoryName', url='$enteredHistoryUrl'")
					historyModel?.historyTitle = enteredHistoryName
					historyModel?.historyUrl = enteredHistoryUrl
					aioBookmark.updateInStorage()

					// Close dialog and notify caller
					close()
					onApply.invoke(true)
				}
			}
		}
	}

	/**
	 * Requests focus and shows the keyboard for the given EditText field.
	 */
	private fun requestFocusOnEditTextField(editField: EditText?) {
		logger.d("Requesting focus on EditText field: $editField")
		editField?.focusable
		editField?.selectAll()
		showOnScreenKeyboard(safeHistoryActivity, editField)
	}

	/**
	 * Displays the dialog for editing a given history entry.
	 *
	 * @param historyModel The history entry to edit.
	 */
	fun show(historyModel: HistoryModel) {
		try {
			logger.d("Preparing to show UpdateHistoryDialog for: ${historyModel.historyUrl}")
			setHistoryModel(historyModel)
			if (dialog.isShowing == false &&
				safeHistoryActivity?.isActivityRunning() == true
			) {
				logger.d("Showing UpdateHistoryDialog.")
				dialog.show()
			}
		} catch (error: Exception) {
			logger.e("Can't show History Update Dialog:", error)
			safeHistoryActivity?.doSomeVibration(50)
			showToast(
				activityInf = safeHistoryActivity,
				msgId = R.string.title_something_went_wrong
			)
		}
	}

	/**
	 * Closes the dialog if visible.
	 */
	fun close() {
		try {
			if (dialog.isShowing &&
				safeHistoryActivity?.isActivityRunning() == true
			) {
				logger.d("Closing UpdateHistoryDialog.")
				dialog.close()
			}
		} catch (error: Exception) {
			logger.e("Can't close History Update Dialog:", error)
			safeHistoryActivity?.doSomeVibration(50)
			showToast(
				activityInf = safeHistoryActivity,
				msgId = R.string.title_something_went_wrong
			)
		}
	}

	/**
	 * Initializes the dialog fields with the provided history entry data.
	 *
	 * @param historyModel History entry to edit
	 */
	fun setHistoryModel(historyModel: HistoryModel) {
		logger.d("Setting history model in dialog: ${historyModel.historyUrl}")
		this.historyModel = historyModel
		this.dialog.view.apply {
			val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
			val editFieldName = findViewById<EditText>(R.id.edit_field_name)
			editFieldName.setText(historyModel.historyTitle)
			editFieldUrl.setText(historyModel.historyUrl)
		}
	}
}
