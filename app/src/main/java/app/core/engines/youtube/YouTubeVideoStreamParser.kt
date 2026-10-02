package app.core.engines.youtube

import lib.process.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.stream.*

object YouTubeVideoStreamParser {
	
	private val logger = LogHelperUtils.from(javaClass)
	
	fun getThumbnail(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): String? {
		logger.d("Fetching thumbnail: $youtubeVideoUrl")
		return try {
			val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeVideoUrl)
			info.thumbnails.firstOrNull()?.url
		} catch (error: Exception) {
			logger.e("Error fetching thumbnail: ${error.localizedMessage}", error)
			onErrorFound(error)
			null
		}
	}
	
	fun getTitle(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): String? {
		logger.d("Fetching title: $youtubeVideoUrl")
		return try {
			val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeVideoUrl)
			info.name
		} catch (error: Exception) {
			logger.e("Error fetching title: ${error.localizedMessage}", error)
			onErrorFound(error)
			null
		}
	}
	
	fun getStreamInfo(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): StreamInfo? {
		logger.d("Fetching stream info: $youtubeVideoUrl")
		return try {
			StreamInfo.getInfo(ServiceList.YouTube, youtubeVideoUrl)
		} catch (error: Exception) {
			logger.e("Error fetching stream info: ${error.localizedMessage}", error)
			onErrorFound(error)
			null
		}
	}
}
