package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.Intent.EXTRA_TEXT
import android.content.Intent.createChooser
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.browser.bookmarks.BookmarkModel
import com.aio.R
import lib.networks.URLUtility.ensureHttps
import lib.networks.URLUtility.isValidURL
import lib.process.LogHelperUtils
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * [BookmarkOptionPopup] provides a contextual popup menu for managing bookmark entries.
 *
 * This popup allows the user to:
 * - Edit the bookmark (change name or URL)
 * - Open the bookmark in the browser
 * - Share the bookmark link with others
 * - Copy the bookmark URL to the clipboard
 * - Delete the bookmark from the library
 *
 * Implementation details:
 * - Uses [PopupBuilder] to build the popup menu UI.
 * - Holds [WeakReference]s to avoid leaking the activity and list view.
 * - Observes lifecycle via [DefaultLifecycleObserver] for automatic cleanup.
 * - Provides debug logging via [LogHelperUtils].
 */
class BookmarkOptionPopup(
	private val bookmarksActivity: BookmarksActivity,
	private val bookmarkModel: BookmarkModel,
	private val listView: View
) : DefaultLifecycleObserver {

	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to the parent activity hosting the bookmarks */
	private val safeBookmarksActivityRef = WeakReference(bookmarksActivity).get()

	/** Weak reference to the bookmark list item view */
	private val safeBookmarkListViewRef = WeakReference(listView).get()

	/** Holds the popup builder instance */
	private var popupBuilder: PopupBuilder? = null

	init {
		// Attach lifecycle observer to clean resources when activity is destroyed
		safeBookmarksActivityRef?.lifecycle?.addObserver(this)

		// Initialize the popup UI
		initializePopup()

		logger.d("BookmarkOptionPopup initialized for: ${bookmarkModel.bookmarkUrl}")
	}

	/** Displays the popup menu */
	fun show() {
		popupBuilder?.show()
		logger.d("Popup shown for bookmark: ${bookmarkModel.bookmarkUrl}")
	}

	/** Hides the popup menu */
	fun close() {
		popupBuilder?.close()
		logger.d("Popup closed for bookmark: ${bookmarkModel.bookmarkUrl}")
	}

	/** Cleans up when the lifecycle owner is destroyed */
	override fun onDestroy(owner: LifecycleOwner) {
		logger.d("Lifecycle destroyed, cleaning up popup resources")
		cleanup()
	}

	/**
	 * Initializes the popup UI and binds it to the bookmark item.
	 * Uses [PopupBuilder] with a predefined layout and anchor view.
	 */
	private fun initializePopup() {
		safeBookmarksActivityRef?.let { activityRef ->
			safeBookmarkListViewRef?.let { listViewRef ->
				popupBuilder = PopupBuilder(
					activityInf = activityRef,
					popupLayoutId = R.layout.activity_bookmarks_1_option_1,
					popupAnchorView = listViewRef.findViewById(R.id.bookmark_url_open_indicator)
				).apply {
					initializePopupButtons(getPopupView())
				}
				logger.d("Popup initialized for bookmark: ${bookmarkModel.bookmarkUrl}")
			}
		}
	}

	/**
	 * Configures popup buttons with their respective actions:
	 * - Edit, Open, Share, Copy, Delete
	 */
	private fun initializePopupButtons(popupView: View?) {
		popupView?.apply {
			mapOf(
				R.id.btn_edit_bookmark to ::editBookmarkInfo,
				R.id.btn_browser_homepage to ::makeDefaultHomepage,
				R.id.btn_open_bookmark to ::openBookmarkInBrowser,
				R.id.btn_share_bookmark to ::shareBookmarkLink,
				R.id.btn_copy_bookmark to ::copyBookmarkInClipboard,
				R.id.btn_delete_bookmark to ::deleteBookmarkFromLibrary
			).forEach { (id, action) ->
				findViewById<View>(id).setOnClickListener {
					logger.d("Button clicked: $id for bookmark: ${bookmarkModel.bookmarkUrl}")
					closeAndCleanup { action() }
				}
			}
			logger.d("Popup buttons initialized for bookmark: ${bookmarkModel.bookmarkUrl}")
		}
	}

	/**
	 * Helper to close popup, execute an action, and perform cleanup.
	 */
	private fun closeAndCleanup(action: (() -> Unit)? = null) {
		logger.d("Closing popup for bookmark: ${bookmarkModel.bookmarkUrl}")
		close()
		action?.invoke()
		cleanup()
		logger.d("Popup closed and cleaned after action for: ${bookmarkModel.bookmarkUrl}")
	}

	/**
	 * Opens a dialog to edit bookmark details (URL or name).
	 * Updates the bookmark list if changes are successfully applied.
	 */
	private fun editBookmarkInfo() {
		safeBookmarksActivityRef?.let { activity ->
			try {
				UpdateBookmarkDialog(
					bookmarkActivity = activity,
					onApply = { result ->
						if (result) {
							activity.updateBookmarkListAdapter()
							showToast(safeBookmarksActivityRef, msgId = R.string.title_updated_successfully)
							logger.d("Bookmark updated successfully: ${bookmarkModel.bookmarkUrl}")
						} else {
							activity.doSomeVibration(50)
							showToast(safeBookmarksActivityRef, msgId = R.string.title_something_went_wrong)
							logger.d("Bookmark update failed for: ${bookmarkModel.bookmarkUrl}")
						}
					}).show(bookmarkModel)
			} catch (error: Exception) {
				activity.doSomeVibration(50)
				showToast(safeBookmarksActivityRef, msgId = R.string.title_something_went_wrong)
				logger.d("Exception while editing bookmark: ${error.message}")
			}
		}
	}

	/**
	 * Make the current web address as the default browser homepage.
	 */
	private fun makeDefaultHomepage() {
		safeBookmarksActivityRef?.let { activity ->
			val bookmarkUrl = bookmarkModel.bookmarkUrl
			logger.d("User entered homepage: $bookmarkUrl")
			if (isValidURL(bookmarkUrl)) {
				val finalNormalizedURL = ensureHttps(bookmarkUrl) ?: bookmarkUrl
				aioSettings.browserDefaultHomepage = finalNormalizedURL
				aioSettings.updateInStorage()
				logger.d("Homepage updated: $finalNormalizedURL")
				showToast(safeBookmarksActivityRef, msgId = R.string.title_updated_successfully)
			} else {
				logger.d("Invalid homepage URL entered")
				activity.doSomeVibration(50)
				showToast(safeBookmarksActivityRef, msgId = R.string.title_invalid_url)
			}
		}
	}

	/**
	 * Copies the bookmark URL into the system clipboard.
	 */
	private fun copyBookmarkInClipboard() {
		safeBookmarksActivityRef?.let { activity ->
			copyTextToClipboard(activity, bookmarkModel.bookmarkUrl)
			showToast(safeBookmarksActivityRef, msgId = R.string.title_copied_url_to_clipboard)
			logger.d("Copied bookmark to clipboard: ${bookmarkModel.bookmarkUrl}")
		}
	}

	/**
	 * Opens the bookmark in the default browser.
	 */
	private fun openBookmarkInBrowser() {
		safeBookmarksActivityRef?.onBookmarkClick(bookmarkModel)
		logger.d("Opened bookmark in browser: ${bookmarkModel.bookmarkUrl}")
	}

	/**
	 * Deletes the bookmark from the library and updates storage.
	 */
	private fun deleteBookmarkFromLibrary() {
		safeBookmarksActivityRef?.let { safeMotherActivityRef ->
			try {
				aioBookmark.getBookmarkLibrary().remove(bookmarkModel)
				aioBookmark.updateInStorage()
				safeMotherActivityRef.updateBookmarkListAdapter()
				showToast(safeBookmarksActivityRef, msgId = R.string.title_successfully_deleted)
				logger.d("Deleted bookmark: ${bookmarkModel.bookmarkUrl}")
			} catch (error: Exception) {
				safeMotherActivityRef.doSomeVibration(20)
				showToast(safeBookmarksActivityRef, msgId = R.string.title_something_went_wrong)
				logger.d("Failed to delete bookmark: ${error.message}")
			}
		}
	}

	/**
	 * Shares the bookmark URL using a system share intent.
	 */
	private fun shareBookmarkLink() {
		safeBookmarksActivityRef?.let { safeMotherActivityRef ->
			try {
				val bookmarkUrl = bookmarkModel.bookmarkUrl
				val shareIntent = Intent().apply {
					action = ACTION_SEND
					putExtra(EXTRA_TEXT, bookmarkUrl)
					type = "text/plain"
				}
				val titleString = getText(R.string.title_share_with_others)
				val intentChooser = createChooser(shareIntent, titleString)
				safeMotherActivityRef.startActivity(intentChooser)
				logger.d("Shared bookmark link: $bookmarkUrl")
			} catch (error: Exception) {
				error.printStackTrace()
				safeMotherActivityRef.doSomeVibration(20)
				showToast(safeBookmarksActivityRef, msgId = R.string.title_something_went_wrong)
				logger.d("Failed to share bookmark: ${error.message}")
			}
		}
	}

	/**
	 * Cleans up popup resources by releasing references.
	 */
	private fun cleanup() {
		popupBuilder = null
		logger.d("Popup resources cleaned up for bookmark: ${bookmarkModel.bookmarkUrl}")
	}
}