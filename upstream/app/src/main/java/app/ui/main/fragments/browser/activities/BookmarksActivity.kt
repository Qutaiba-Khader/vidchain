package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.View.GONE
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOKeyStrings.ACTIVITY_RESULT_KEY
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.browser.bookmarks.BookmarkModel
import app.ui.main.fragments.browser.activities.BookmarkAdapter.OnBookmarkItemClick
import app.ui.main.fragments.browser.activities.BookmarkAdapter.OnBookmarkItemLongClick
import com.aio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.hideOnScreenKeyboard
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * BookmarksActivity manages the display and interaction with browser bookmarks.
 *
 * This activity provides a comprehensive interface for viewing, searching, and managing
 * saved browser bookmarks. It displays bookmarks in a scrollable list format with
 * support for search functionality, pagination, and individual bookmark operations.
 *
 * Key Features:
 * - Display of bookmarks with titles, URLs, favicons, and creation dates
 * - Real-time search functionality with text filtering
 * - Pagination with "Load More" button for large bookmark collections
 * - Individual bookmark operations via long-press context menu
 * - Bulk operations (delete all bookmarks)
 * - Integration with AIOTimer for periodic UI updates
 * - Memory-safe implementation using weak references
 *
 * Architecture:
 * - Uses BaseActivity as foundation for consistent activity behavior
 * - Implements AIOTimerListener for periodic UI refresh
 * - Uses BookmarkAdapter for efficient list view management
 * - Follows observer pattern for user interaction callbacks
 */
class BookmarksActivity : BaseActivity(),
	AIOTimerListener, OnBookmarkItemClick, OnBookmarkItemLongClick {

	/** Logger instance for tracking activity lifecycle events and user interactions */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to prevent memory leaks in timer callbacks and async operations
	 * This ensures the activity can be garbage collected when not in use
	 */
	private val safeSelfReference = WeakReference(this)

	/**
	 * Safe accessor for the activity reference with null safety
	 * Used throughout the class to avoid potential NPEs
	 */
	private val safeBookmarksActivityRef = safeSelfReference.get()

	// UI components declaration
	private lateinit var buttonActionbarSearch: View          // Search toggle button in action bar
	private lateinit var buttonActionbarSearchImg: ImageView  // Search icon image (changes state)
	private lateinit var containerSearchLayout: View          // Container for search UI elements
	private lateinit var containerSearchEditField: View       // Container for search text field
	private lateinit var editTextSearchField: EditText        // Input field for search terms
	private lateinit var buttonSearchAction: View             // Search action button (if needed)
	private lateinit var emptyBookmarksIndicator: View        // View shown when no bookmarks exist
	private lateinit var bookmarkListView: ListView           // Main list view displaying bookmarks
	private lateinit var buttonLoadMoreBookmarks: View        // Button to load additional bookmarks

	/**
	 * Popup menu for bookmark options displayed on long-press
	 * Null when not visible, created on demand
	 */
	private var bookmarkOptionPopup: BookmarkOptionPopup? = null

	/**
	 * Lazy initialized adapter for managing bookmark display in the list view
	 * Initialized only when first accessed to improve startup performance
	 */
	private val bookmarksAdapter by lazy {
		logger.d("Lazy initialization: Creating BookmarkAdapter instance")
		val activityRef = safeBookmarksActivityRef
		BookmarkAdapter(activityRef, activityRef, activityRef)
	}

	/**
	 * Provides the layout resource ID for this activity's UI.
	 * Called during the activity rendering phase to inflate the appropriate layout.
	 *
	 * @return The layout resource ID (R.layout.activity_bookmarks_1)
	 */
	override fun onRenderingLayout(): Int {
		logger.d("Layout rendering: Inflating activity_bookmarks_1 layout resource")
		return R.layout.activity_bookmarks_1
	}

	/**
	 * Called after the layout has been rendered and views are available.
	 * Initializes UI components and sets up event listeners.
	 * This is the main initialization point for the activity's UI.
	 */
	override fun onAfterLayoutRender() {
		logger.d("Post-layout initialization: Setting up views and event handlers")
		initializeViews()
		initializeViewsOnClickEvents()
	}

	/**
	 * Called when the activity is resumed and becomes visible to the user.
	 * Registers with AIOTimer for periodic updates and refreshes the bookmark list.
	 */
	override fun onResumeActivity() {
		super.onResumeActivity()
		logger.d("Activity resumed: Registering for timer updates and refreshing data")
		registerToAIOTimer()
		updateBookmarkListAdapter()
	}

	/**
	 * Called when the activity is paused and no longer in foreground.
	 * Unregisters from AIOTimer to prevent unnecessary updates and save resources.
	 */
	override fun onPauseActivity() {
		super.onPauseActivity()
		logger.d("Activity paused: Unregistering from timer to conserve resources")
		unregisterToAIOTimer()
	}

	/**
	 * Handles the hardware/software back button press event.
	 * Provides custom back navigation: closes search if open, otherwise closes activity.
	 */
	override fun onBackPressActivity() {
		logger.d("Back button pressed: Determining navigation action")
		if (containerSearchLayout.isVisible) {
			logger.d("Search container visible - closing search interface")
			hideSearchContainer()
		} else {
			logger.d("No special views visible - closing activity with fade animation")
			closeActivityWithFadeAnimation(false)
		}
	}

	/**
	 * Called when the activity is being destroyed.
	 * Performs comprehensive cleanup of resources, references, and registered components.
	 * Prevents memory leaks by nullifying adapters and closing popups.
	 */
	override fun onDestroy() {
		logger.d("Activity destruction: Cleaning up resources and references")
		unregisterToAIOTimer()
		bookmarkListView.adapter = null
		bookmarkOptionPopup?.close()
		bookmarkOptionPopup = null
		super.onDestroy()
	}

	/**
	 * Called periodically by AIOTimer for UI updates.
	 * Used to refresh dynamic UI elements that may change based on external factors.
	 *
	 * @param loopCount The current iteration count from the timer (can be used for timing logic)
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		logger.d("Timer tick received (loop: $loopCount): Updating UI state")
		updateLoadMoreButtonVisibility()
	}

	/**
	 * Handles bookmark item click events.
	 * Opens the selected bookmark URL in the browser and closes the activity.
	 *
	 * @param bookmarkModel The bookmark model that was clicked, containing URL and metadata
	 */
	override fun onBookmarkClick(bookmarkModel: BookmarkModel) {
		logger.d("Bookmark clicked: Preparing to open URL - ${bookmarkModel.bookmarkUrl}")
		openBookmarkInBrowser(bookmarkModel)
		closeActivityWithFadeAnimation(shouldAnimate = true)
	}

	/**
	 * Handles bookmark item long-click events.
	 * Shows a context menu with options for the selected bookmark (delete, edit, etc.).
	 *
	 * @param bookmarkModel The bookmark model that was long-clicked
	 * @param position The adapter position of the item in the list
	 * @param listView The ListView containing the item (for positioning the popup)
	 */
	override fun onBookmarkLongClick(
		bookmarkModel: BookmarkModel,
		position: Int, listView: View
	) {
		logger.d("Bookmark long-clicked at position $position: Showing options menu")
		safeBookmarksActivityRef?.let { safeBookmarkActivityRef ->
			try {
				safeBookmarkActivityRef.doSomeVibration(50)
				bookmarkOptionPopup = BookmarkOptionPopup(
					bookmarksActivity = safeBookmarkActivityRef,
					bookmarkModel = bookmarkModel,
					listView = listView
				).apply { show() }
				logger.d("Options popup successfully created and displayed")
			} catch (error: Exception) {
				logger.d("Error displaying options popup: ${error.message}")
				error.printStackTrace()
				showToast(
					activityInf = safeBookmarksActivityRef,
					msgId = R.string.title_something_went_wrong
				)
			}
		}
	}

	/**
	 * Clears the weak activity reference to prevent memory leaks.
	 * Called as part of the cleanup process when the activity is being destroyed.
	 */
	override fun clearWeakActivityReference() {
		logger.d("Clearing weak activity reference to prevent memory leaks")
		safeSelfReference.clear()
		super.clearWeakActivityReference()
	}

	/**
	 * Initializes all UI components by finding views from the layout.
	 * Sets up initial visibility states and prepares components for interaction.
	 */
	private fun initializeViews() {
		logger.d("Initializing UI components: Finding views and setting initial state")
		safeBookmarksActivityRef?.let {
			// Action bar and search components
			buttonActionbarSearch = findViewById(R.id.btn_actionbar_search)
			buttonActionbarSearchImg = findViewById(R.id.btn_actionbar_search_img)
			containerSearchLayout = findViewById(R.id.top_searchbar_container)
			editTextSearchField = findViewById<EditText>(R.id.edit_search_keywords_text)
			containerSearchEditField = findViewById(R.id.edit_search_keywords_container)
			containerSearchLayout.visibility = GONE
			initSearchKeywordsWatcher(editTextSearchField)

			// Bookmark list and status components
			emptyBookmarksIndicator = findViewById(R.id.empty_bookmarks_indicator)
			bookmarkListView = findViewById(R.id.list_bookmarks)
			buttonLoadMoreBookmarks = findViewById(R.id.btn_load_more_bookmarks)

			// Configure list view with adapter
			bookmarkListView.adapter = bookmarksAdapter
			bookmarkListView.visibility = GONE
			emptyBookmarksIndicator.visibility = GONE
			buttonLoadMoreBookmarks.visibility = GONE

			// Initial UI state update
			updateLoadMoreButtonVisibility()
			logger.d("UI components initialized successfully")
		}
	}

	/**
	 * Sets up click event listeners for various UI components.
	 * Attaches handlers for user interactions with buttons and other interactive elements.
	 */
	private fun initializeViewsOnClickEvents() {
		logger.d("Setting up click event listeners for interactive elements")

		// Search toggle button
		buttonActionbarSearch.setOnClickListener {
			logger.d("Action bar search button clicked: Toggling search interface")
			toggleSearchEditFieldVisibility()
		}

		// Search field container click (focus and select all)
		containerSearchEditField.setOnClickListener {
			logger.d("Search field container clicked: Focusing and selecting text")
			editTextSearchField.focusable
			editTextSearchField.selectAll()
			showOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
		}

		// Back navigation button
		findViewById<View>(R.id.btn_left_actionbar)
			.setOnClickListener {
				logger.d("Left action bar button clicked: Navigating back")
				onBackPressActivity()
			}

		// Clear all bookmarks button
		findViewById<View>(R.id.btn_right_actionbar)
			.setOnClickListener {
				logger.d("Right action bar button clicked: Initiating delete all operation")
				deleteAllBookmarks()
			}

		// Load more bookmarks button
		buttonLoadMoreBookmarks.setOnClickListener {
			logger.d("Load More button clicked: Loading additional bookmarks")
			bookmarksAdapter.loadMoreBookmarks(/* searchTerms = */ null)
			showToast(
				activityInf = safeBookmarksActivityRef,
				msgId = R.string.title_loaded_successfully
			)
		}
	}

	/**
	 * Initializes text watcher for search field to provide real-time filtering.
	 * Monitors text changes and triggers bookmark filtering as user types.
	 *
	 * @param editTextSearchField The search input field to monitor
	 */
	private fun initSearchKeywordsWatcher(editTextSearchField: EditText) {
		logger.d("Initializing search text watcher for real-time filtering")
		editTextSearchField.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
				// No action needed before text changes
			}

			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				// No action needed during text change
			}

			override fun afterTextChanged(editableField: Editable?) {
				val searchTerms = editableField?.toString()?.lowercase()
				if (searchTerms.isNullOrEmpty()) {
					logger.d("Search field cleared: Resetting to show all bookmarks")
					bookmarksAdapter.resetBookmarkAdapter()
					bookmarksAdapter.loadMoreBookmarks(/* searchTerms = */ null)
					return
				}
				logger.d("Search terms entered: '$searchTerms' - Filtering bookmarks")
				bookmarksAdapter.resetBookmarkAdapter()
				bookmarksAdapter.loadMoreBookmarks(searchTerms)
			}
		})
	}

	/**
	 * Toggles the visibility of the search container.
	 * Provides a unified interface for showing/hiding search functionality.
	 */
	private fun toggleSearchEditFieldVisibility() {
		if (containerSearchLayout.isVisible) {
			logger.d("Search container visible - Hiding search interface")
			hideSearchContainer()
		} else {
			logger.d("Search container hidden - Showing search interface")
			showSearchContainer()
		}
	}

	/**
	 * Shows the search container and focuses the search field.
	 * Animates the appearance and automatically shows the keyboard for immediate input.
	 */
	private fun showSearchContainer() {
		logger.d("Showing search container with animation")
		buttonActionbarSearchImg.setImageResource(R.drawable.ic_button_actionbar_cancel)
		showView(containerSearchLayout, true)
		delay(200, object : OnTaskFinishListener {
			override fun afterDelay() {
				logger.d("Search container shown - Displaying on-screen keyboard")
				showOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
			}
		})
	}

	/**
	 * Hides the search container and dismisses the keyboard.
	 * Clears any search terms and resets the search interface to initial state.
	 */
	private fun hideSearchContainer() {
		CoroutineScope(Dispatchers.Main).launch {
			logger.d("Hiding empty box indicator is visible")
			if (emptyBookmarksIndicator.isVisible) {
				emptyBookmarksIndicator.visibility = GONE
			}

			logger.d("Hiding search container and clearing search terms")
			editTextSearchField.setText("")
			hideView(containerSearchLayout, true)
			hideOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
			buttonActionbarSearchImg.setImageResource(R.drawable.ic_button_actionbar_search)
		}
	}

	/**
	 * Shows a confirmation dialog for deleting all bookmarks.
	 * Provides safety mechanism to prevent accidental data loss.
	 * If confirmed, clears all bookmarks from both memory and persistent storage.
	 */
	private fun deleteAllBookmarks() {
		logger.d("Initiating delete all bookmarks operation: Showing confirmation dialog")
		MsgDialogUtils.getMessageDialog(
			baseActivityInf = safeBookmarksActivityRef,
			isTitleVisible = true,
			titleTextViewCustomize =
				{ it.setText(R.string.title_are_you_sure_about_this) },
			messageTxt = getText(R.string.text_delete_bookmarks_confirmation),
			isNegativeButtonVisible = false,
			positiveButtonTextCustomize = {
				it.setText(R.string.title_clear_now)
				it.setLeftSideDrawable(R.drawable.ic_button_clear)
			}
		)?.apply {
			setOnClickForPositiveButton {
				logger.d("User confirmed deletion: Clearing all bookmarks from storage")
				close()
				aioBookmark.getBookmarkLibrary().clear()
				aioBookmark.updateInStorage()
				bookmarksAdapter.resetBookmarkAdapter()
			}
		}?.show()
	}

	/**
	 * Refreshes the bookmark list adapter with current data.
	 * Resets the adapter and reloads bookmarks, typically called after data changes.
	 */
	fun updateBookmarkListAdapter() {
		logger.d("Refreshing bookmark list adapter with current data")
		bookmarksAdapter.resetBookmarkAdapter()
		if (containerSearchLayout.isVisible) {
			val enteredSearchTerms = editTextSearchField.text.toString()
			if (enteredSearchTerms.isNotEmpty()) {
				bookmarksAdapter.loadMoreBookmarks(enteredSearchTerms)
			}
		} else {
			bookmarksAdapter.loadMoreBookmarks(/* searchTerms = */ null)
		}
	}

	/**
	 * Opens a bookmark in the browser and closes the activity.
	 * Sends the bookmark URL back to the calling fragment for handling.
	 *
	 * @param bookmarkModel The bookmark model containing the URL to open
	 */
	private fun openBookmarkInBrowser(bookmarkModel: BookmarkModel) {
		logger.d("Opening bookmark in browser: ${bookmarkModel.bookmarkUrl}")
		sendResultBackToBrowserFragment(bookmarkModel.bookmarkUrl)
	}

	/**
	 * Sends the selected bookmark URL back to the calling browser fragment.
	 * Uses activity result mechanism to communicate with the parent fragment.
	 *
	 * @param result The URL string to send back to the browser fragment
	 */
	fun sendResultBackToBrowserFragment(result: String) {
		logger.d("Sending result back to browser fragment: $result")
		setResult(RESULT_OK, Intent().apply {
			putExtra(ACTIVITY_RESULT_KEY, result)
		}).let { onBackPressActivity() }
	}

	/**
	 * Registers this activity with AIOTimer for periodic updates.
	 * Ensures the activity receives timer events for UI refresh.
	 */
	private fun registerToAIOTimer() {
		logger.d("Registering activity with AIOTimer for periodic updates")
		safeBookmarksActivityRef?.let { aioTimer.register(it) }
	}

	/**
	 * Unregisters this activity from AIOTimer to stop updates.
	 * Prevents unnecessary callbacks when the activity is not visible.
	 */
	private fun unregisterToAIOTimer() {
		logger.d("Unregistering activity from AIOTimer to stop updates")
		safeBookmarksActivityRef?.let { aioTimer.unregister(it) }
	}

	/**
	 * Updates the visibility of the Load More button and empty state indicator.
	 * Dynamically adjusts UI based on the current bookmark count and adapter state.
	 * Called periodically and after data changes to maintain consistent UI state.
	 */
	private fun updateLoadMoreButtonVisibility() {
		logger.d("Search operation is currently active, hiding the load more button")
		if (containerSearchLayout.isVisible) {
			hideView(buttonLoadMoreBookmarks, true)
			findViewById<TextView>(R.id.txt_empty_bookmark_indicator)
				.text = getText(R.string.title_no_bookmark_matched_with_search)
		} else {
			findViewById<TextView>(R.id.txt_empty_bookmark_indicator)
				.text = getText(R.string.title_your_bookmark_collection_empty)
		}

		val bookmarkSize = if (containerSearchLayout.isVisible) bookmarksAdapter.count
		else aioBookmark.getBookmarkLibrary().size
		logger.d("Updating UI state: adapterCount=${bookmarksAdapter.count}, totalBookmarks=$bookmarkSize")

		// Load More button visibility logic
		if (bookmarksAdapter.count >= bookmarkSize) {
			logger.d("All bookmarks loaded - Hiding Load More button")
			hideView(buttonLoadMoreBookmarks, true)
		} else {
			logger.d("More bookmarks available - Showing Load More button")
			showView(buttonLoadMoreBookmarks, true)
		}

		// Empty state and list visibility logic
		if (bookmarksAdapter.isEmpty) {
			logger.d("No bookmarks to display - Showing empty state indicator")
			showView(emptyBookmarksIndicator, true)
		} else {
			logger.d("Bookmarks available - Hiding empty state, showing list")
			hideView(emptyBookmarksIndicator, true).let {
				delay(500, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("Animation complete - Showing bookmark list view")
						showView(bookmarkListView, true)
					}
				})
			}
		}
	}
}