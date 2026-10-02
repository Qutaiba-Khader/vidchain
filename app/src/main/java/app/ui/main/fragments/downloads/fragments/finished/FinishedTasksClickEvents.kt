package app.ui.main.fragments.downloads.fragments.finished

import app.core.engines.downloader.DownloadDataModel

interface FinishedTasksClickEvents {
	fun onFinishedDownloadClick(downloadModel: DownloadDataModel)
	fun onFinishedDownloadLongClick(downloadModel: DownloadDataModel)
}
