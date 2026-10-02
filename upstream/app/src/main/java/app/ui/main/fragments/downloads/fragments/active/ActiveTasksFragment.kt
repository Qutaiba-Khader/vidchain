package app.ui.main.fragments.downloads.fragments.active

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.core.AIOApp
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseFragment
import app.core.engines.downloader.DownloadDataModel
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.DownloadsFragment
import com.aio.R
import java.lang.ref.WeakReference

open class ActiveTasksFragment : BaseFragment(), AIOTimerListener {

	private val fragmentWeakRef = WeakReference(this)

	val safeMotherActivityRef: MotherActivity?
		get() = safeBaseActivityRef as? MotherActivity

	val safeActiveTasksFragmentRef: ActiveTasksFragment?
		get() = safeBaseFragmentRef as? ActiveTasksFragment

	open val activeTasksListViewContainer: LinearLayout? by lazy {
		safeFragmentLayoutRef?.findViewById(R.id.container_download_tasks_queue)
	}

	override fun getLayoutResId(): Int {
		return R.layout.frag_down_3_active_1
	}

	override fun onAfterLayoutLoad(layoutView: View, state: Bundle?) {
		initViewsClickEvents(layoutView)
		registerToDownloadSystemUI()
	}

	override fun onResumeFragment() {
		registerToDownloadSystemUI()
		selfRegisterToParentFragment()
		safeActiveTasksFragmentRef?.let { AIOApp.aioTimer.register(it) }
	}

	override fun onPauseFragment() {
		unregisterFromDownloadSystemUI()
		selfRegisterToParentFragment()
		safeActiveTasksFragmentRef?.let { AIOApp.aioTimer.unregister(it) }
	}

	override fun onDestroyView() {
		unregisterFromDownloadSystemUI()
		super.onDestroyView()
	}

	override fun onAIOTimerTick(loopCount: Double) {
		if (downloadSystem.activeDownloadDataModels.isEmpty()) {
			openToFinishedTab()
		}
	}

	private fun openToFinishedTab() {
		val downloadFragment = parentFragment as? DownloadsFragment
		downloadFragment?.openFinishedTab()
	}

	private fun registerToDownloadSystemUI() {
		downloadSystem.downloadsUIManager.activeTasksFragment = null
		downloadSystem.downloadsUIManager.activeTasksFragment = fragmentWeakRef.get()
		downloadSystem.downloadsUIManager.redrawEverything()
	}

	private fun unregisterFromDownloadSystemUI() {
		downloadSystem.downloadsUIManager.cleanupOrphans()
		downloadSystem.downloadsUIManager.activeTasksFragment = null
	}

	fun onDownloadUIItemClick(downloadModel: DownloadDataModel) {
		ActiveTasksOptions(motherActivity = safeMotherActivityRef).show(downloadModel)
	}

	private fun selfRegisterToParentFragment() {
		val downloadFragment = parentFragment as? DownloadsFragment
		downloadFragment?.activeTasksFragment = safeActiveTasksFragmentRef
		downloadFragment?.safeFragmentLayoutRef?.let {
			val title = it.findViewById<TextView>(R.id.txt_current_frag_name)
			title?.setText(R.string.title_active_downloads)
		}
	}

	private fun initViewsClickEvents(layoutView: View) {
		layoutView.findViewById<View>(R.id.btn_go_back_finished_tasks)
			.setOnClickListener { safeMotherActivityRef?.onBackPressActivity() }
	}
}
