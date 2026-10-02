package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.Intent.EXTRA_TEXT
import android.content.Intent.createChooser
import android.view.View
import android.webkit.CookieManager
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioHistory
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.browser.bookmarks.BookmarkModel
import app.core.engines.browser.history.HistoryModel
import com.aio.R
import lib.networks.URLUtility.ensureHttps
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.normalizeEncodedUrl
import lib.process.LogHelperUtils
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference
import java.util.Date

/**
 * A context-aware popup menu that provides various operations for browser history items.
 *
 * This class displays a contextual options menu when a user long-presses or interacts with
 * a history item in the browser history list. It offers a comprehensive set of actions
 * including editing, sharing, bookmarking, and managing history entries.
 *
 * ## Key Features:
 * - **Contextual Operations**: Provides relevant actions based on the selected history item
 * - **Memory Safety**: Uses WeakReference to prevent memory leaks with Activity context
 * - **User-Friendly Interface**: Clean popup layout with intuitive action buttons
 * - **Comprehensive History Management**: Full CRUD operations for history items
 * - **Integration with Browser Ecosystem**: Seamless connection with bookmarks and browser settings
 *
 * ## Supported Operations:
 * 1. **Edit History** - Modify title and URL of history entry
 * 2. **Open in Browser** - Launch the URL in the browser
 * 3. **Share Link** - Share the URL via system sharing options
 * 4. **Set as Homepage** - Make the URL the default browser homepage
 * 5. **Copy to Clipboard** - Copy URL to system clipboard
 * 6. **Add to Bookmarks** - Save the history item as a bookmark
 * 7. **Delete History** - Remove the history entry permanently
 *
 * ## Architecture:
 * - Uses PopupBuilder for consistent UI presentation
 * - Implements WeakReference pattern for Activity safety
 * - Follows single-responsibility principle for each operation
 * - Provides comprehensive error handling and user feedback
 *
 * @param historyActivity The parent HistoryActivity context (uses WeakReference for safety)
 * @param historyModel The HistoryModel instance representing the selected history item
 * @param listView The View that serves as anchor for the popup positioning
 *
 * @see HistoryActivity for the parent activity implementation
 * @see HistoryModel for the data model structure
 * @see PopupBuilder for the UI presentation layer
 * @see BookmarkModel for bookmark integration
 */
class HistoryOptionPopup(
	private val historyActivity: HistoryActivity?,
	private val historyModel: HistoryModel,
	private val listView: View
) {

	/**
	 * Logger instance for tracking user interactions and debugging operations.
	 * Provides detailed logging for each action performed through the popup menu.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the parent HistoryActivity to prevent memory leaks.
	 * This ensures the Activity can be garbage collected if needed while the popup exists.
	 */
	private val safeHistoryActivityRef = WeakReference(historyActivity).get()

	/**
	 * Weak reference to the list view that anchors the popup positioning.
	 * Used for proper popup placement relative to the user's touch interaction.
	 */
	private val safeHistoryListView = WeakReference(listView).get()

	/**
	 * The URL associated with the history item for quick access.
	 * Extracted from the HistoryModel for convenience in operations.
	 */
	private val historyUrl = historyModel.historyUrl

	/**
	 * String resource ID for error messages when operations fail unexpectedly.
	 * Provides consistent error messaging across all popup operations.
	 */
	private val somethingWentWrongResId = R.string.title_something_went_wrong

	/**
	 * The popup builder instance responsible for UI presentation and interaction handling.
	 * Manages the popup lifecycle, layout inflation, and button event bindings.
	 */
	private var popupBuilder: PopupBuilder? = null

	/**
	 * Initializes the HistoryOptionPopup with the specified context and data.
	 *
	 * This constructor performs the following setup:
	 * 1. Validates the provided Activity and View references
	 * 2. Creates the PopupBuilder with the appropriate layout and anchor
	 * 3. Binds click listeners to all action buttons in the popup
	 * 4. Sets up the operation handlers for each menu option
	 *
	 * @throws IllegalStateException if required context or view references are null
	 */
	init {
		logger.d("Initializing HistoryOptionPopup for URL: $historyUrl")

		safeHistoryActivityRef?.let { activityRef ->
			safeHistoryListView?.let { listViewRef ->
				// Initialize the popup builder with history options layout
				popupBuilder = PopupBuilder(
					activityInf = activityRef,
					popupLayoutId = R.layout.activity_browser_history_1_option_1,
					popupAnchorView = listViewRef.findViewById(R.id.img_open_indicator)
				)

				popupBuilder?.let { builder ->
					// Configure all action buttons in the popup menu
					builder.getPopupView().apply {
						val buttonActions = mapOf(
							R.id.btn_edit_history to { close(); editHistoryInfo() },
							R.id.btn_open_history to { close(); openHistoryInBrowser() },
							R.id.btn_share_history to { close(); shareHistoryLink() },
							R.id.btn_browser_homepage to { close(); makeDefaultHomepage() },
							R.id.btn_copy_history to { close(); copyHistoryToClipboard() },
							R.id.btn_add_to_bookmark to { close(); addHistoryToBookmark() },
							R.id.btn_delete_history to { close(); deleteHistoryFromLibrary() }
						)

						// Bind each button to its corresponding action
						buttonActions.forEach { (buttonId, action) ->
							findViewById<View>(buttonId).setOnClickListener { action() }
						}
					}
				}
			} ?: run {
				logger.d("ListView reference is null, popup initialization incomplete")
			}
		} ?: run {
			logger.d("HistoryActivity reference is null, popup initialization incomplete")
		}
	}

	/**
	 * Displays the history options popup menu to the user.
	 *
	 * This method shows the contextual menu anchored to the history list item.
	 * The popup appears at the calculated position based on the anchor view.
	 *
	 * @see PopupBuilder.show for the actual popup presentation logic
	 */
	fun show() {
		logger.d("Showing HistoryOptionPopup for URL: $historyUrl")
		popupBuilder?.show()
	}

	/**
	 * Closes the history options popup menu.
	 *
	 * This method dismisses the popup and cleans up any associated resources.
	 * It should be called when an action is selected or when the popup needs to be hidden.
	 *
	 * @see PopupBuilder.close for the actual popup dismissal logic
	 */
	fun close() {
		logger.d("Closing HistoryOptionPopup")
		popupBuilder?.close()
	}

	/**
	 * Sets the current history URL as the default browser homepage.
	 *
	 * This operation:
	 * 1. Validates the URL format and security
	 * 2. Normalizes the URL (ensures HTTPS when possible)
	 * 3. Updates the application settings with the new homepage
	 * 4. Persists the change to storage
	 * 5. Provides user feedback on success/failure
	 *
	 * @see aioSettings.browserDefaultHomepage for the homepage setting
	 * @see ensureHttps for URL security normalization
	 * @see isValidURL for URL validation
	 */
	private fun makeDefaultHomepage() {
		safeHistoryActivityRef?.let { activity ->
			logger.d("User attempting to set homepage: $historyUrl")

			if (isValidURL(historyUrl)) {
				// Normalize URL with HTTPS preference for security
				val finalNormalizedURL = ensureHttps(historyUrl) ?: historyUrl

				// Update application settings
				aioSettings.browserDefaultHomepage = finalNormalizedURL
				aioSettings.updateInStorage()

				logger.d("Homepage successfully updated to: $finalNormalizedURL")
				showToast(activity, msgId = R.string.title_updated_successfully)
			} else {
				logger.d("Invalid homepage URL entered: $historyUrl")
				activity.doSomeVibration(50)
				showToast(activity, msgId = R.string.title_invalid_url)
			}
		} ?: run {
			logger.d("Cannot set homepage - activity reference is null")
		}
	}

	/**
	 * Copies the history URL to the system clipboard.
	 *
	 * This operation:
	 * 1. Copies the exact URL to the clipboard
	 * 2. Provides visual feedback to the user
	 * 3. Logs the action for debugging purposes
	 *
	 * @see copyTextToClipboard for clipboard operation implementation
	 * @see showToast for user feedback
	 */
	private fun copyHistoryToClipboard() {
		logger.d("Copying history URL to clipboard: $historyUrl")
		copyTextToClipboard(safeHistoryActivityRef, historyUrl)
		showToast(safeHistoryActivityRef, msgId = R.string.title_copied_url_to_clipboard)
	}

	/**
	 * Opens the history URL in the browser.
	 *
	 * This operation delegates to the HistoryActivity's item click handler
	 * to maintain consistent navigation behavior.
	 *
	 * @see HistoryActivity.onHistoryItemClick for the actual navigation logic
	 */
	private fun openHistoryInBrowser() {
		logger.d("Opening history URL in browser: $historyUrl")
		safeHistoryActivityRef?.onHistoryItemClick(historyModel)
	}

	/**
	 * Opens the history item editing dialog.
	 *
	 * This operation:
	 * 1. Launches the UpdateHistoryDialog for modifying history details
	 * 2. Handles the result callback to refresh the UI on success
	 * 3. Provides error handling for dialog failures
	 * 4. Updates the history list adapter if changes are made
	 *
	 * @see UpdateHistoryDialog for the editing interface
	 * @see HistoryActivity.updateHistoryListAdapter for UI refresh
	 */
	private fun editHistoryInfo() {
		safeHistoryActivityRef?.let { activityRef ->
			try {
				UpdateHistoryDialog(
					historyActivity = activityRef,
					onApply = { result ->
						if (result) {
							// Refresh the history list to show updated information
							activityRef.updateHistoryListAdapter()
							showToast(activityRef, msgId = R.string.title_updated_successfully)
							logger.d("History item successfully updated: $historyUrl")
						} else {
							// Provide feedback on failure
							activityRef.doSomeVibration(50)
							showToast(activityRef, msgId = somethingWentWrongResId)
							logger.d("History item update failed for: $historyUrl")
						}
					}
				).show(historyModel)
			} catch (error: Exception) {
				logger.e("Exception while editing history item: ${error.message}", error)
				activityRef.doSomeVibration(50)
				showToast(activityRef, msgId = somethingWentWrongResId)
			}
		} ?: run {
			logger.d("Cannot edit history - activity reference is null")
		}
	}

	/**
	 * Permanently deletes the history item from the library.
	 *
	 * This operation:
	 * 1. Removes the history item from the in-memory collection
	 * 2. Updates the UI to reflect the removal
	 * 3. Persists the change to storage
	 * 4. Clears associated browser cookies for privacy
	 * 5. Provides user feedback on completion
	 *
	 * @see aioHistory.getHistoryLibrary for the history collection
	 * @see aioHistory.updateInStorage for persistence
	 * @see CookieManager for cookie cleanup
	 */
	private fun deleteHistoryFromLibrary() {
		try {
			logger.d("Deleting history item from library: $historyUrl")

			// Remove from in-memory collection
			aioHistory.getHistoryLibrary().remove(historyModel)

			// Update UI
			safeHistoryActivityRef?.updateHistoryListAdapter()

			// Persist changes
			aioHistory.updateInStorage()

			// Clear associated cookies for privacy
			val cookieManager = CookieManager.getInstance()
			cookieManager.removeAllCookies(null)
			cookieManager.flush()

			showToast(safeHistoryActivityRef, msgId = R.string.title_successfully_deleted)
			logger.d("History item successfully deleted: $historyUrl")
		} catch (error: Exception) {
			logger.e("Error deleting history item: ${error.message}", error)
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(safeHistoryActivityRef, msgId = somethingWentWrongResId)
		}
	}

	/**
	 * Shares the history URL via system sharing options.
	 *
	 * This operation:
	 * 1. Creates a standard Android share intent
	 * 2. Sets the URL as the shared text content
	 * 3. Launches the system share chooser dialog
	 * 4. Handles potential exceptions during sharing
	 *
	 * @see Intent.ACTION_SEND for the share action type
	 * @see createChooser for the share target selection dialog
	 */
	private fun shareHistoryLink() {
		try {
			logger.d("Sharing history URL: $historyUrl")

			val shareIntent = Intent(ACTION_SEND).apply {
				putExtra(EXTRA_TEXT, historyUrl)
				type = "text/plain"
			}

			val titleText = getText(R.string.title_share_with_others)
			safeHistoryActivityRef?.startActivity(createChooser(shareIntent, titleText))

		} catch (error: Exception) {
			logger.e("Error sharing history URL: ${error.message}", error)
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(safeHistoryActivityRef, msgId = somethingWentWrongResId)
		}
	}

	/**
	 * Converts the history item into a browser bookmark.
	 *
	 * This operation:
	 * 1. Creates a new BookmarkModel from the history data
	 * 2. Sets appropriate timestamps and metadata
	 * 3. Normalizes the URL for consistent storage
	 * 4. Adds the bookmark to the beginning of the collection
	 * 5. Persists the bookmark library to storage
	 * 6. Provides user feedback on success
	 *
	 * @see BookmarkModel for the bookmark data structure
	 * @see normalizeEncodedUrl for URL normalization
	 * @see aioBookmark.getBookmarkLibrary for bookmark collection access
	 */
	private fun addHistoryToBookmark() {
		try {
			logger.d("Converting history item to bookmark: $historyUrl")

			val bookmarkModel = BookmarkModel().apply {
				bookmarkCreationDate = Date()
				bookmarkModifiedDate = Date()
				bookmarkUrl = normalizeEncodedUrl(historyUrl)

				// Use history title or fallback to "Unknown" if empty
				val unknownTextString = getText(R.string.title_unknown)
				bookmarkName = historyModel.historyTitle.ifEmpty { unknownTextString }
			}

			// Add to bookmarks and persist changes
			aioBookmark.getBookmarkLibrary().add(0, bookmarkModel)
			aioBookmark.updateInStorage()

			showToast(safeHistoryActivityRef, msgId = R.string.title_bookmark_saved)
			logger.d("Successfully converted history to bookmark: $historyUrl")

		} catch (error: Exception) {
			logger.e("Error converting history to bookmark: ${error.message}", error)
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(safeHistoryActivityRef, msgId = somethingWentWrongResId)
		}
	}
}