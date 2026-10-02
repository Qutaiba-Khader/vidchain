package app.ui.main.fragments.downloads

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.viewpager.widget.ViewPager
import app.core.AIOApp.Companion.downloadSystem
import app.core.bases.BaseFragment
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.video_parser.dialogs.VideoLinkPasteEditor
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.fragments.active.ActiveTasksFragment
import app.ui.main.fragments.downloads.fragments.finished.FinishedTasksFragment
import com.aio.R
import lib.device.SecureFileUtil.authenticate
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import java.lang.ref.WeakReference

/**
 * DownloadsFragment handles the UI and logic for managing download-related tabs (active/finished),
 * interactions with the main activity, and banner ads display.
 */
open class DownloadsFragment : BaseFragment() {

	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to the host activity to avoid memory leaks
	private val safeMotherActivityRef by lazy { WeakReference(safeBaseActivityRef as MotherActivity).get() }

	// Weak reference to this fragment instance for safe usage across contexts
	private val safeDownloadFragmentRef by lazy { WeakReference(this).get() }

	private lateinit var fragmentLayoutView: View                   // Root layout view of this fragment
	open lateinit var fragmentViewPager: ViewPager                  // ViewPager to host child fragments
	open var finishedTasksFragment: FinishedTasksFragment? = null   // Reference to finished downloads fragment
	open var activeTasksFragment: ActiveTasksFragment? = null       // Reference to active downloads fragment

	/**
	 * Returns the layout resource ID for this fragment.
	 */
	override fun getLayoutResId(): Int {
		return R.layout.frag_down_1_main_1
	}

	/**
	 * Called after the fragment layout is inflated and ready.
	 * Initializes views, ads, child fragments, and click events.
	 */
	override fun onAfterLayoutLoad(layoutView: View, state: Bundle?) {
		registerSelfReferenceInMotherActivity()
		initializeViewProperties(layoutView)
		initializeChildFragments()
		initializeOnClickEvents(layoutView)
	}

	/**
	 * Called when fragment becomes visible again.
	 */
	override fun onResumeFragment() {
		registerSelfReferenceInMotherActivity()
	}

	/**
	 * Called when fragment goes into background. No operation is required here.
	 */
	override fun onPauseFragment() {
		// Do nothing
	}

	/**
	 * Cleans up references and adapter to prevent memory leaks.
	 */
	override fun onDestroyView() {
		unregisterSelfReferenceInMotherActivity()
		clearFragmentAdapterFromMemory()
		super.onDestroyView()
	}

	/**
	 * Initializes internal references and AdMob view if the activity is valid.
	 */
	private fun initializeViewProperties(layoutView: View) {
		safeMotherActivityRef?.let {
			fragmentLayoutView = layoutView
			initializeViews(layoutView)
		}
	}

	/**
	 * Registers this fragment instance in MotherActivity for later reference.
	 */
	private fun registerSelfReferenceInMotherActivity() {
		safeMotherActivityRef?.downloadFragment = safeDownloadFragmentRef
		safeMotherActivityRef?.sideNavigation?.closeDrawerNavigation()
	}

	/**
	 * Removes fragment reference from MotherActivity when not in use.
	 */
	private fun unregisterSelfReferenceInMotherActivity() {
		safeMotherActivityRef?.downloadFragment = null
	}

	/**
	 * Clears the ViewPager adapter to allow garbage collection of fragments.
	 */
	private fun clearFragmentAdapterFromMemory() {
		fragmentViewPager.adapter = null
	}

	/**
	 * Binds view components from the layout.
	 */
	private fun initializeViews(layoutView: View) {
		fragmentViewPager = layoutView.findViewById(R.id.fragment_viewpager)
		togglePrivateFilesButtonUI()
	}

	/**
	 * Initializes child fragments inside the ViewPager.
	 */
	private fun initializeChildFragments() {
		safeDownloadFragmentRef?.let {
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				fragmentViewPager.adapter = DownloadFragmentAdapter(childFragmentManager)
				fragmentViewPager.offscreenPageLimit = 1 // Cache only one offscreen fragment
			}
		}
	}

	/**
	 * Assigns click actions to UI elements such as "Back" and "Add Download" buttons.
	 */
	private fun initializeOnClickEvents(layoutView: View) {
		val clickActionsMap = mapOf(
			layoutView.findViewById<View>(R.id.btn_actionbar_add_download) to { showDownloadTaskEditorDialog() },
			layoutView.findViewById<View>(R.id.btn_toggle_private_files) to { togglePrivateFiles() },
			layoutView.findViewById<View>(R.id.btn_actionbar_back) to {
				if (fragmentViewPager.currentItem == 1) openFinishedTab() else navigateToBrowserFragment()
			}
		)

		clickActionsMap.forEach { (view, action) ->
			view.setOnClickListener { action() }
		}
	}

	/**
	 * Navigates the user to the browser fragment.
	 */
	private fun navigateToBrowserFragment() {
		safeMotherActivityRef?.openBrowserFragment()
	}

	/**
	 * Shows the dialog for adding a new download task manually via pasted video URL.
	 */
	private fun showDownloadTaskEditorDialog() {
		safeMotherActivityRef?.let { safeActivityRef ->
			VideoLinkPasteEditor(safeActivityRef).show()
		}
	}

	/**
	 * Switches ViewPager to show the Finished Downloads tab.
	 */
	fun openFinishedTab() {
		if (fragmentViewPager.currentItem != 0) {
			fragmentViewPager.currentItem = 0
		}
	}

	/**
	 * Switches ViewPager to show the Active Downloads tab.
	 */
	fun openActiveTab() {
		if (fragmentViewPager.currentItem != 1) {
			fragmentViewPager.currentItem = 1
		}
	}

	fun togglePrivateFilesButtonUI() {
		try {
			updatePrivateFilesCountUI()

			val txt = safeFragmentLayoutRef
				?.findViewById<TextView>(R.id.txt_private_toggle) ?: return

			if (finishedTasksFragment == null) {
				txt.text = getText(R.string.title_toggle_private_files)
				return
			}

			if (isShowingPrivateFiles) {
				txt.text = getText(R.string.title_closed_privates)
				txt.setLeftSideDrawable(R.drawable.ic_button_lock)
			} else {
				txt.text = getText(R.string.title_open_privates)
				txt.setLeftSideDrawable(R.drawable.ic_button_unlock_v1)
			}

		} catch (error: Exception) {
			logger.e("Error while toggling private files title", error)
		}
	}


	fun updatePrivateFilesCountUI() {
		val txtPrivateFileCounterId = R.id.txt_private_file_counter
		val tv = safeFragmentLayoutRef?.findViewById<TextView>(txtPrivateFileCounterId) ?: return

		val totalPrivateFiles = downloadSystem.finishedDownloadDataModels.count { model ->
			model.globalSettings.defaultDownloadLocation == PRIVATE_FOLDER
		}
		tv.text = totalPrivateFiles.toString()
		showView(tv, true, 300)
	}

	var isShowingPrivateFiles = false
	fun togglePrivateFiles() {
		openFinishedTab()
		val adapter = finishedTasksFragment?.finishedTasksListAdapter ?: return

		if (isShowingPrivateFiles) {
			adapter.setFilter { it.globalSettings.defaultDownloadLocation == SYSTEM_GALLERY }
			isShowingPrivateFiles = false
		} else {
			authenticate(safeBaseActivityRef) { ok ->
				if (ok) {
					adapter.setFilter { it.globalSettings.defaultDownloadLocation == PRIVATE_FOLDER }
					isShowingPrivateFiles = true
					togglePrivateFilesButtonUI()
				}
			}
			return
		}
		togglePrivateFilesButtonUI()
	}

}