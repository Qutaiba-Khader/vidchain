package app.core.engines.youtube

import app.core.engines.youtube.YTVideosModelsDBManager.getAllYouTubeVideoDataModels
import lib.process.*

class YouTubeTrendingVideos {
	
	private val logger = LogHelperUtils.from(javaClass)
	val listOfCachedFeatVideos by lazy { getAllYouTubeVideoDataModels() }
	
	@Synchronized
	fun refreshTrendingVideos() {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 5000,
			codeBlock = {
			
			}
		)
	}
	
}