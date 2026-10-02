package app.ui.main.fragments.downloads.fragments.finished

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import app.core.AIOApp.Companion.aioRawFiles
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseFragment
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.DownloadsFragment
import app.ui.main.guides.GuidePlatformPicker
import com.aio.R
import com.airbnb.lottie.LottieAnimationView
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.showView

class FinishedTasksFragment : BaseFragment(), FinishedTasksClickEvents, AIOTimerListener {

	private val logger = LogHelperUtils.from(javaClass)

	private var containerEmptyDownloads: View? = null
	private var btnOpenActiveDownloads: View? = null
	private var btnHowToDownload: View? = null
	private var btnClosePrivateFiles: View? = null
	private var animOpenActiveDownloads: LottieAnimationView? = null
	private var listViewDownloads: ListView? = null
	private var lastCheckedFinishedTasks = 0
	var finishedTasksListAdapter: FinishedTasksListAdapter? = null

	val safeMotherActivityRef: MotherActivity?
		get() = safeBaseActivityRef as? MotherActivity

	val safeFinishTasksFragment: FinishedTasksFragment?
		get() = safeBaseFragmentRef as? FinishedTasksFragment

	override fun getLayoutResId() = R.layout.frag_down_4_finish_1

	override fun onAfterLayoutLoad(layoutView: View, state: Bundle?) {
		initializeViewsAndListAdapter(layoutView)
	}

	override fun onResumeFragment() {
		registerIntoDownloadSystem()
		registerToDownloadFragment()
		safeFinishTasksFragment?.let {
			aioTimer.register(it)
		}
	}

	override fun onPauseFragment() {
		lastCheckedFinishedTasks = -2
		safeFinishTasksFragment?.let {
			aioTimer.unregister(it)
		}
	}

	override fun onDestroyView() {
		finishedTasksListAdapter?.clearResources()
		listViewDownloads?.adapter = null
		finishedTasksListAdapter = null

		listViewDownloads = null
		containerEmptyDownloads = null
		btnOpenActiveDownloads = null
		btnHowToDownload = null
		animOpenActiveDownloads = null

		safeFinishTasksFragment?.let { aioTimer.unregister(it) }
		unregisterIntoDownloadSystem()
		unregisterToDownloadFragment()

		super.onDestroyView()
	}

	override fun onAIOTimerTick(loopCount: Double) {
		if (!isAdded || isDetached) return
		if (safeFinishTasksFragment == null) return
		if (!isFragmentRunning) return

		val downloadsFragment = parentFragment as? DownloadsFragment
		updateDownloadFragmentTitle(downloadsFragment)
		updateDownloadFragmentPrivateButtonText(downloadsFragment)
		toggleEmptyListVisibility(containerEmptyDownloads, listViewDownloads)
		toggleClosePrivateButtonVisibility(btnClosePrivateFiles)
		toggleOpenActiveTasksButtonVisibility(btnOpenActiveDownloads)
	}

	override fun onFinishedDownloadClick(downloadModel: DownloadDataModel) {
		val activityRef = safeMotherActivityRef
		val fragment = safeFinishTasksFragment
		if (activityRef == null) return
		if (fragment == null) return

		downloadModel.hasUserOpenedTheFile = true
		downloadModel.updateInStorage()

		val isSingleClickEnabled = aioSettings.openDownloadedFileOnSingleClick
		val opts = FinishedDownloadOptions(fragment)
		opts.initialize()

		if (isSingleClickEnabled) {
			opts.setDownloadModel(downloadModel)
			opts.playTheMedia()
		} else {
			opts.show(downloadModel)
		}
	}

	override fun onFinishedDownloadLongClick(downloadModel: DownloadDataModel) {
		val activityRef = safeMotherActivityRef
		val fragment = safeFinishTasksFragment
		if (activityRef == null) return
		if (fragment == null) return

		downloadModel.hasUserOpenedTheFile = true
		downloadModel.updateInStorage()

		val opts = FinishedDownloadOptions(fragment)
		opts.initialize()
		opts.show(downloadModel)

		activityRef.doSomeVibration()
	}

	private fun initializeViewsAndListAdapter(layout: View) {
		val motherActivity = safeMotherActivityRef
		val fragment = safeFinishTasksFragment
		if (motherActivity == null) return
		if (fragment == null) return

		containerEmptyDownloads = layout.findViewById(R.id.container_empty_downloads)
		btnHowToDownload = layout.findViewById(R.id.btn_how_to_download)
		btnClosePrivateFiles = layout.findViewById(R.id.btn_close_private_files)
		btnOpenActiveDownloads = layout.findViewById(R.id.btn_open_active_downloads)
		animOpenActiveDownloads = layout.findViewById(R.id.img_open_active_downloads)
		listViewDownloads = layout.findViewById(R.id.container_download_tasks_finished)

		btnHowToDownload?.setOnClickListener { GuidePlatformPicker(motherActivity).show() }
		btnOpenActiveDownloads?.setOnClickListener { openActiveTasksFragment() }
		btnClosePrivateFiles?.setOnClickListener { triggerTogglingPrivateFiles() }

		loadOpenActiveTasksAnimation()

		finishedTasksListAdapter = FinishedTasksListAdapter(fragment)
		listViewDownloads?.adapter = finishedTasksListAdapter
		finishedTasksListAdapter?.setFilter { downloadModel ->
			val downloadConfigs = downloadModel.globalSettings
			downloadConfigs.defaultDownloadLocation == SYSTEM_GALLERY
		}

		toggleEmptyListVisibility(containerEmptyDownloads, listViewDownloads)
		toggleOpenActiveTasksButtonVisibility(btnOpenActiveDownloads)
	}

	private fun openActiveTasksFragment() {
		(parentFragment as? DownloadsFragment)?.openActiveTab()
	}

	private fun toggleOpenActiveTasksButtonVisibility(button: View?) {
		button ?: return
		val activeModels = downloadSystem.activeDownloadDataModels
		val shouldVisible = activeModels.isNotEmpty()
		if (shouldVisible != button.isVisible) {
			if (shouldVisible) showView(button, true, 300)
			else hideView(button, true, 300)
		}
	}

	private fun triggerTogglingPrivateFiles() {
		val fragment = safeFinishTasksFragment
		val motherActivity = safeMotherActivityRef

		if (!isFragmentRunning) return
		if (fragment == null) return
		if (motherActivity == null) return

		val downloadsFragment = parentFragment as? DownloadsFragment ?: return
		val isPrivateFolderActive = (downloadsFragment).isShowingPrivateFiles
		if (!isPrivateFolderActive) return
		downloadsFragment.togglePrivateFiles()
	}

	private fun toggleClosePrivateButtonVisibility(btnView: View?) {
		val fragment = safeFinishTasksFragment
		val motherActivity = safeMotherActivityRef

		if (!isFragmentRunning) return
		if (fragment == null) return
		if (motherActivity == null) return
		if (btnView == null) return

		val downloadsFragment = parentFragment as? DownloadsFragment ?: return
		val isPrivateFolderActive = (downloadsFragment).isShowingPrivateFiles
		val visibility = if (isPrivateFolderActive) View.VISIBLE else View.GONE
		btnView.visibility = visibility
	}

	private fun toggleEmptyListVisibility(emptyView: View?, listView: View?) {
		if (downloadSystem.isInitializing) return
		if (!isFragmentRunning) return
		if (emptyView == null || listView == null) return

		if (getFinishedDownloadModels().isEmpty()) {
			hideView(listView, true, 100)
			showView(emptyView, true, 300)
		} else {
			hideView(emptyView, true, 100)
			showView(listView, true, 300)
		}

		finishedTasksListAdapter?.notifyDataSetChangedOnSort(false)
	}

	private fun registerToDownloadFragment() {
		(parentFragment as? DownloadsFragment)?.let {
			it.finishedTasksFragment = this
			updateDownloadFragmentTitle(it)
		}
	}

	private fun unregisterToDownloadFragment() {
		(parentFragment as? DownloadsFragment)?.finishedTasksFragment = null
	}

	private fun registerIntoDownloadSystem() {
		val downloadsUIManager = downloadSystem.downloadsUIManager
		downloadsUIManager.finishedTasksFragment = safeFinishTasksFragment
	}

	private fun unregisterIntoDownloadSystem() {
		val downloadsUIManager = downloadSystem.downloadsUIManager
		downloadsUIManager.finishedTasksFragment = null
	}

	fun getFinishedDownloadModels(): ArrayList<DownloadDataModel> {
		return downloadSystem.finishedDownloadDataModels
	}

	fun updateDownloadFragmentTitle(fragment: DownloadsFragment?) {
		val container = fragment?.safeFragmentLayoutRef ?: return
		if (!isFragmentRunning) return

		val total = getFinishedDownloadModels().size
		if (total == lastCheckedFinishedTasks && total > 0) return

		val title = container.findViewById<TextView>(R.id.txt_current_frag_name)
		val fixedName = getText(R.string.title_total_downloads)
		val text = "$fixedName ($total)"
		title?.text = text

		lastCheckedFinishedTasks = total
	}

	fun updateDownloadFragmentPrivateButtonText(fragment: DownloadsFragment?) {
		if (!isFragmentRunning) return
		fragment?.togglePrivateFilesButtonUI()
	}

	private fun loadOpenActiveTasksAnimation() {
		animOpenActiveDownloads?.apply {
			clipToCompositionBounds = false
			setScaleType(ImageView.ScaleType.FIT_XY)

			aioRawFiles.getDownloadFoundAnimationComposition()?.let {
				setComposition(it)
				playAnimation()
			} ?: setAnimation(R.raw.animation_videos_found)

			showView(this, true, 100)
		}
	}
}