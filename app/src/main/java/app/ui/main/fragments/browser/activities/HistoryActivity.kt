package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.view.View
import android.view.View.GONE
import android.widget.ListView
import app.core.AIOApp.Companion.aioHistory
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOKeyStrings.ACTIVITY_RESULT_KEY
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.browser.history.HistoryModel
import app.ui.main.fragments.browser.activities.HistoryAdapter.OnHistoryItemClick
import app.ui.main.fragments.browser.activities.HistoryAdapter.OnHistoryItemLongClick
import com.aio.R
import lib.process.CommonTimeUtils
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.LogHelperUtils
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * [HistoryActivity] is responsible for displaying and managing
 * the user's browsing history within the application.
 *
 * Features:
 * - Display a list of browsing history entries.
 * - Allow user to open or delete history items.
 * - Support long-press options (e.g., delete, open in browser).
 * - Provides a "Load More" button for paginated history loading.
 * - Shows a confirmation dialog for deleting all history.
 */
class HistoryActivity : BaseActivity(),
	AIOTimerListener, OnHistoryItemClick, OnHistoryItemLongClick {

	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks. */
	private val safeSelfReference = WeakReference(this)
	private val safeHistoryActivityRef = safeSelfReference.get()

	private lateinit var emptyHistoryIndicator: View
	private lateinit var historyList: ListView
	private lateinit var buttonLoadMoreHistory: View

	private var historyOptionPopup: HistoryOptionPopup? = null

	private val arg = safeHistoryActivityRef
	val historyAdapter by lazy { HistoryAdapter(arg, arg, arg) }

	/** Defines which layout to render for this activity. */
	override fun onRenderingLayout(): Int = R.layout.activity_browser_history_1

	/** Called after layout is rendered, initializes views and click listeners. */
	override fun onAfterLayoutRender() {
		logger.d("Initializing HistoryActivity views and listeners")
		initializeViews()
		initializeViewsOnClickEvents()
	}

	/** Lifecycle: resume activity */
	override fun onResumeActivity() {
		super.onResumeActivity()
		logger.d("Resumed HistoryActivity, registering AIOTimer and updating adapter")
		registerToAIOTimer()
		updateHistoryListAdapter()
	}

	/** Lifecycle: pause activity */
	override fun onPauseActivity() {
		super.onPauseActivity()
		logger.d("Paused HistoryActivity, unregistering AIOTimer")
		unregisterToAIOTimer()
	}

	/** Handle back press */
	override fun onBackPressActivity() {
		logger.d("Back pressed, closing HistoryActivity")
		closeActivityWithFadeAnimation(false)
	}

	/** Cleanup when activity is destroyed */
	override fun onDestroy() {
		logger.d("Destroying HistoryActivity, cleaning up resources")
		unregisterToAIOTimer()
		historyList.adapter = null
		historyOptionPopup?.close()
		historyOptionPopup = null
		super.onDestroy()
	}

	/** Clears weak self-reference */
	override fun clearWeakActivityReference() {
		logger.d("Clearing weak activity reference for HistoryActivity")
		safeSelfReference.clear()
		super.clearWeakActivityReference()
	}

	/** Called on each AIOTimer tick to update the UI. */
	override fun onAIOTimerTick(loopCount: Double) {
		logger.d("AIOTimer tick received, updating load more button visibility")
		updateLoadMoreButtonVisibility()
	}

	/** Handles history item click -> open in browser. */
	override fun onHistoryItemClick(historyModel: HistoryModel) {
		logger.d("History item clicked: ${historyModel.historyUrl}")
		openHistoryInBrowser(historyModel)
	}

	/** Handles history item long click -> show options popup. */
	override fun onHistoryItemLongClick(historyModel: HistoryModel, position: Int, listView: View) {
		safeHistoryActivityRef?.let { safeActivityRef ->
			try {
				logger.d("History item long clicked at position $position: ${historyModel.historyUrl}")
				historyOptionPopup = HistoryOptionPopup(
					historyActivity = safeActivityRef,
					historyModel = historyModel,
					listView = listView
				)
				safeActivityRef.doSomeVibration(50)
				historyOptionPopup?.show()
			} catch (error: Exception) {
				error.printStackTrace()
				logger.d("Failed to show history option popup: ${error.message}")
				showToast(
					activityInf = safeActivityRef,
					msgId = R.string.title_something_went_wrong
				)
			}
		}
	}

	/** Initializes activity views and sets up default visibility. */
	private fun initializeViews() {
		safeHistoryActivityRef?.let {
			logger.d("Initializing history list and UI components")
			emptyHistoryIndicator = findViewById(R.id.empty_history_indicator)
			historyList = findViewById(R.id.list_history)
			buttonLoadMoreHistory = findViewById(R.id.btn_load_more_history)

			historyList.adapter = historyAdapter
			historyList.visibility = GONE

			emptyHistoryIndicator.visibility = GONE
			buttonLoadMoreHistory.visibility = GONE
			updateLoadMoreButtonVisibility()
		}
	}

	/** Attaches onClick listeners to buttons and action bar. */
	private fun initializeViewsOnClickEvents() {
		logger.d("Setting up click listeners for HistoryActivity UI")
		findViewById<View>(R.id.btn_left_actionbar)
			.setOnClickListener { onBackPressActivity() }

		findViewById<View>(R.id.btn_right_actionbar)
			.setOnClickListener { deleteAllHistory() }

		buttonLoadMoreHistory.setOnClickListener {
			logger.d("Load More button clicked")
			historyAdapter.loadMoreHistory()
			showToast(
				activityInf = safeHistoryActivityRef,
				msgId = R.string.title_loaded_successfully
			)
		}
	}

	/** Shows confirmation dialog to delete all history. */
	private fun deleteAllHistory() {
		logger.d("Attempting to delete all history")
		MsgDialogUtils.getMessageDialog(
			baseActivityInf = safeHistoryActivityRef,
			isTitleVisible = true,
			titleTextViewCustomize = { it.setText(R.string.title_are_you_sure_about_this) },
			messageTxt = getText(R.string.text_delete_browsing_history_confirmation),
			isNegativeButtonVisible = false,
			positiveButtonTextCustomize = {
				it.setText(R.string.title_clear_now)
				it.setLeftSideDrawable(R.drawable.ic_button_clear)
			})?.apply {
			setOnClickForPositiveButton {
				logger.d("User confirmed history deletion")
				close()
				aioHistory.getHistoryLibrary().clear()
				aioHistory.updateInStorage()
				historyAdapter.resetHistoryAdapter()
			}
		}?.show()
	}

	/** Registers this activity to AIOTimer updates. */
	private fun registerToAIOTimer() {
		logger.d("Registering HistoryActivity to AIOTimer")
		safeHistoryActivityRef?.let { aioTimer.register(it) }
	}

	/** Unregisters this activity from AIOTimer updates. */
	private fun unregisterToAIOTimer() {
		logger.d("Unregistering HistoryActivity from AIOTimer")
		safeHistoryActivityRef?.let { aioTimer.unregister(it) }
	}

	/** Refreshes history adapter with latest history data. */
	fun updateHistoryListAdapter() {
		logger.d("Updating history list adapter with fresh data")
		historyAdapter.resetHistoryAdapter()
		historyAdapter.loadMoreHistory()
	}

	/** Updates visibility of 'Load More' and empty state indicators. */
	private fun updateLoadMoreButtonVisibility() {
		val historySize = aioHistory.getHistoryLibrary().size
		logger.d("Updating UI visibility: loaded=${historyAdapter.count}, total=$historySize")

		if (historyAdapter.count >= historySize) hideView(buttonLoadMoreHistory, true)
		else showView(buttonLoadMoreHistory, true)

		if (historyAdapter.isEmpty) showView(emptyHistoryIndicator, true)
		else hideView(emptyHistoryIndicator, true).let {
			CommonTimeUtils.delay(500, object : OnTaskFinishListener {
				override fun afterDelay() {
					showView(historyList, true)
					logger.d("History list is now visible")
				}
			})
		}
	}

	/** Opens a selected history item in the browser. */
	private fun openHistoryInBrowser(historyModel: HistoryModel) {
		logger.d("Opening history in browser: ${historyModel.historyUrl}")
		sendResultBackToBrowserFragment(historyModel.historyUrl)
		onBackPressActivity()
	}

	/** Sends selected history item back to BrowserFragment. */
	fun sendResultBackToBrowserFragment(result: String) {
		logger.d("Sending selected history URL back to BrowserFragment: $result")
		setResult(RESULT_OK, Intent().apply {
			putExtra(ACTIVITY_RESULT_KEY, result)
		}).let { onBackPressActivity() }
	}
}
