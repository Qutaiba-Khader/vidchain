package app.core.engines.downloader

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.children
import app.core.AIOApp
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.fragments.active.ActiveTasksFragment
import app.ui.main.fragments.downloads.fragments.finished.FinishedTasksFragment
import com.aio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.process.LogHelperUtils

class DownloadUIManager(private val downloadSystem: DownloadSystem) {

	private val logger = LogHelperUtils.from(javaClass)

	var safeMotherActivity: MotherActivity? = null
	var activeTasksFragment: ActiveTasksFragment? = null
	var finishedTasksFragment: FinishedTasksFragment? = null
	var loadingDownloadModelTextview: TextView? = null

	private val constantRowTagId = R.id.tag_download_id
	private val constantUIManagerTagId = R.id.tag_download_ui_manager

	private fun getUIManager(view: View): DownloaderRowUIManager? {
		return view.getTag(constantUIManagerTagId) as? DownloaderRowUIManager
	}

	private fun getDownloadId(view: View): Int? {
		return view.getTag(constantRowTagId) as? Int
	}

	@Synchronized
	fun redrawEverything() {
		safeMotherActivity?.getAttachedCoroutineScope()?.launch {
			withContext(Dispatchers.IO) {
				cleanupOrphans()

				withContext(Dispatchers.Main) {
					val viewContainer = getActiveListViewContainer()
					viewContainer?.let { container ->
						container.children.forEach { view -> getUIManager(view)?.clearResources() }
						container.removeAllViews()
					}

					downloadSystem.activeDownloadDataModels.forEach { dataModel ->
						addNewActiveUI(dataModel)
					}
				}
			}
		}
	}

	@Synchronized
	fun addNewActiveUI(downloadModel: DownloadDataModel, position: Int = -1) {
		safeMotherActivity?.getAttachedCoroutineScope()?.launch(Dispatchers.Main) {
			val rowUI = generateActiveUI(downloadModel)
			configureActiveUI(rowUI, downloadModel)
			val viewContainer = getActiveListViewContainer()
			if (position != -1) viewContainer?.addView(rowUI, position)
			else viewContainer?.addView(rowUI)
		}
	}

	@Synchronized
	fun updateActiveUI(downloadModel: DownloadDataModel) {
		safeMotherActivity?.getAttachedCoroutineScope()?.launch(Dispatchers.Main) {
			val viewContainer = getActiveListViewContainer()
			val downloadId = downloadModel.downloadId
			val resultedRow = findViewByDownloadId(viewContainer, downloadId)
			if (resultedRow != null) configureActiveUI(resultedRow, downloadModel)
			else logger.d("Update requested for ID $downloadId but UI view not found. Skipping.")
		}
	}

	@SuppressLint("InflateParams")
	private fun generateActiveUI(downloadModel: DownloadDataModel): View {
		val rowUI = if (safeMotherActivity == null) {
			val inflater = LayoutInflater.from(AIOApp.INSTANCE)
			inflater.inflate(R.layout.frag_down_3_active_1_row_1, null)
		} else {
			val themedCtx = ContextThemeWrapper(safeMotherActivity, R.style.style_application)
			val inflater = LayoutInflater.from(themedCtx)
			inflater.inflate(R.layout.frag_down_3_active_1_row_1, null)
		}

		val activeFrag = activeTasksFragment
		rowUI.apply {
			setTag(constantRowTagId, downloadModel.downloadId)
			isClickable = true
			setOnClickListener { activeFrag?.onDownloadUIItemClick(downloadModel) }
			setOnLongClickListener {
				activeFrag?.safeMotherActivityRef?.doSomeVibration()
				activeFrag?.onDownloadUIItemClick(downloadModel)
				true
			}
			val dpValue = 0f
			val pixels = dpValue * context.resources.displayMetrics.density
			val layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
			layoutParams.apply { setMargins(0, 0, 0, pixels.toInt()) }
			this.layoutParams = layoutParams
		}

		return rowUI
	}

	@Synchronized
	fun removeActiveUI(downloadModel: DownloadDataModel) {
		val viewContainer = getActiveListViewContainer()
		viewContainer?.let { container ->
			val resultedRow = findViewByDownloadId(container, downloadModel.downloadId)
			if (resultedRow != null) {
				getUIManager(resultedRow)?.clearResources()
				container.removeView(resultedRow)
			}
		}
	}

	@Synchronized
	fun cleanupOrphans() {
		val viewContainer = getActiveListViewContainer()
		val fragmentRunning = activeTasksFragment?.isFragmentRunning ?: false
		val activityScope = safeMotherActivity?.getAttachedCoroutineScope()

		if (viewContainer != null && !fragmentRunning && activityScope != null) {
			activityScope.launch(Dispatchers.Main) {
				viewContainer.children.toList().forEach { view ->
					getUIManager(view)?.clearResources()
					viewContainer.removeView(view)
				}
			}
		}
	}

	private fun getActiveListViewContainer(): LinearLayout? {
		return activeTasksFragment?.activeTasksListViewContainer
	}

	private fun findViewByDownloadId(container: ViewGroup?, downloadId: Int): View? {
		return container?.children?.firstOrNull { getDownloadId(it) == downloadId }
	}

	@Synchronized
	private fun configureActiveUI(rowUI: View, downloadModel: DownloadDataModel) {
		var rowUIManager = getUIManager(rowUI)

		if (rowUIManager == null) {
			rowUIManager = DownloaderRowUIManager(rowUI)
			rowUI.setTag(constantUIManagerTagId, rowUIManager)
		}

		rowUIManager.apply { updateView(downloadModel) }
	}
}