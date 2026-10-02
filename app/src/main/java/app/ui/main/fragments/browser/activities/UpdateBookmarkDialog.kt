package app.ui.main.fragments.browser.activities

import android.view.View
import android.widget.EditText
import app.core.AIOApp.Companion.aioBookmark
import app.core.engines.browser.bookmarks.BookmarkModel
import com.aio.R
import lib.networks.URLUtility.isValidURL
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView
import java.lang.ref.WeakReference

/**
 * Dialog for updating an existing bookmark (name and URL).
 *
 * Features:
 * - Uses WeakReference to avoid leaking the hosting activity.
 * - Validates input fields before saving.
 * - Updates bookmark in persistent storage upon confirmation.
 * - Provides vibration and toast feedback for invalid input.
 *
 * Usage:
 *   val dialog = UpdateBookmarkDialog(activity) { success -> ... }
 *   dialog.show(bookmarkModel)
 */
class UpdateBookmarkDialog(
	private val bookmarkActivity: BookmarksActivity,
	private val onApply: (Boolean) -> Unit = {}
) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the activity */
	private val safeBookmarkActivity = WeakReference(bookmarkActivity).get()
	private var bookmarkModel: BookmarkModel? = null

	/** Lazily created dialog with input fields for editing bookmark name & URL */
	private val dialog by lazy {
		DialogBuilder(safeBookmarkActivity).apply {
			setView(R.layout.dialog_bookmark_edit_1)
			setCancelable(true)

			view.apply {
				val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
				val editFieldName = findViewById<EditText>(R.id.edit_field_name)

				// Request focus for URL input field
				findViewById<View>(R.id.container_edit_field_url).setOnClickListener {
					logger.d("URL container clicked, requesting focus.")
					requestFocusOnEditTextField(editFieldUrl)
				}

				// Request focus for Name input field
				findViewById<View>(R.id.container_edit_field_name).setOnClickListener {
					logger.d("Name container clicked, requesting focus.")
					requestFocusOnEditTextField(editFieldName)
				}

				// Save / Apply button
				findViewById<View>(R.id.btn_dialog_positive_container).setOnClickListener {
					logger.d("Apply button clicked, validating inputs.")

					val enteredBookmarkName = editFieldName.text.toString()
					if (enteredBookmarkName.isEmpty()) {
						logger.d("Validation failed: bookmark name is empty.")
						safeBookmarkActivity?.doSomeVibration(50)
						ToastView.showToast(
							activityInf = safeBookmarkActivity,
							msgId = R.string.title_bookmark_name_can_not_be_empty
						)
						return@setOnClickListener
					}

					val enteredBookmarkUrl = editFieldUrl.text.toString()
					if (enteredBookmarkUrl.isEmpty()) {
						logger.d("Validation failed: bookmark URL is empty.")
						safeBookmarkActivity?.doSomeVibration(50)
						ToastView.showToast(
							activityInf = safeBookmarkActivity,
							msgId = R.string.title_bookmark_url_can_not_be_empty
						)
						return@setOnClickListener
					}

					if (!isValidURL(enteredBookmarkUrl)) {
						logger.d("Validation failed: invalid bookmark URL = $enteredBookmarkUrl")
						safeBookmarkActivity?.doSomeVibration(50)
						ToastView.showToast(
							activityInf = safeBookmarkActivity,
							msgId = R.string.title_bookmark_url_not_valid
						)
						return@setOnClickListener
					}

					// Apply changes to the bookmark model
					logger.d("Updating bookmark: name='$enteredBookmarkName', url='$enteredBookmarkUrl'")
					bookmarkModel?.bookmarkName = enteredBookmarkName
					bookmarkModel?.bookmarkUrl = enteredBookmarkUrl
					aioBookmark.updateInStorage()

					// Close dialog and notify caller
					close()
					onApply.invoke(true)
				}
			}
		}
	}

	/**
	 * Requests focus and shows keyboard for the given EditText field.
	 */
	private fun requestFocusOnEditTextField(editFieldUrl: EditText?) {
		logger.d("Requesting focus on EditText field: $editFieldUrl")
		editFieldUrl?.focusable
		editFieldUrl?.selectAll()
		showOnScreenKeyboard(safeBookmarkActivity, editFieldUrl)
	}

	/**
	 * Displays the dialog for editing a given bookmark.
	 *
	 * @param bookmarkModel The bookmark to edit.
	 */
	fun show(bookmarkModel: BookmarkModel) {
		try {
			logger.d("Preparing to show UpdateBookmarkDialog for: ${bookmarkModel.bookmarkUrl}")
			setBookmarkModel(bookmarkModel)
			if (dialog.isShowing == false &&
				safeBookmarkActivity?.isActivityRunning() == true
			) {
				logger.d("Showing UpdateBookmarkDialog.")
				dialog.show()
			}
		} catch (error: Exception) {
			logger.e("Can't show Bookmark Update Dialog:", error)
			safeBookmarkActivity?.doSomeVibration(50)
			ToastView.showToast(
				activityInf = safeBookmarkActivity,
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
				safeBookmarkActivity?.isActivityRunning() == true
			) {
				logger.d("Closing UpdateBookmarkDialog.")
				dialog.close()
			}
		} catch (error: Exception) {
			logger.e("Can't close bookmark update dialog:", error)
			safeBookmarkActivity?.doSomeVibration(50)
			ToastView.showToast(
				activityInf = safeBookmarkActivity,
				msgId = R.string.title_something_went_wrong
			)
		}
	}

	/**
	 * Initializes the dialog fields with the provided bookmark data.
	 *
	 * @param bookmarkModel Bookmark to edit
	 */
	fun setBookmarkModel(bookmarkModel: BookmarkModel) {
		logger.d("Setting bookmark model in dialog: ${bookmarkModel.bookmarkUrl}")
		this.bookmarkModel = bookmarkModel
		this.dialog.view.apply {
			val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
			val editFieldName = findViewById<EditText>(R.id.edit_field_name)
			editFieldName.setText(bookmarkModel.bookmarkName)
			editFieldUrl.setText(bookmarkModel.bookmarkUrl)
		}
	}
}