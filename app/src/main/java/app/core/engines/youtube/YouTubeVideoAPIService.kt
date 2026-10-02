package app.core.engines.youtube

import lib.device.DateTimeUtils.formatVideoDuration
import lib.process.*
import lib.texts.CommonTextUtils.formatViewCounts
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.localization.*
import org.schabi.newpipe.extractor.services.youtube.linkHandler.*
import org.schabi.newpipe.extractor.stream.*

object YouTubeVideoAPIService {
	
	private val logger = LogHelperUtils.from(javaClass)
	
	fun fetchingTopTrendingVideos() {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 5000,
			codeBlock = {
				fetchTrendingVideos()
			}
		)
	}
	
	@JvmStatic
	fun fetchTrendingVideos(countryCode: String = "IN"): List<YouTubeVideoDataModel> {
		val start = System.currentTimeMillis()
		
		return try {
			val kioskList = ServiceList.YouTube.getKioskList()
			
			val kioskIds = listOf(
				YoutubeTrendingMoviesAndShowsTrailersLinkHandlerFactory.KIOSK_ID,
				YoutubeTrendingGamingVideosLinkHandlerFactory.KIOSK_ID,
				YoutubeTrendingMusicLinkHandlerFactory.KIOSK_ID,
				YoutubeTrendingPodcastsEpisodesLinkHandlerFactory.KIOSK_ID
			)
			
			val allItems = mutableListOf<StreamInfoItem>()
			
			for (id in kioskIds) {
				val extractor = kioskList.getExtractorById(id, null)
				extractor.forceContentCountry(ContentCountry(countryCode))
				
				val t0 = System.currentTimeMillis()
				extractor.fetchPage()
				val t1 = System.currentTimeMillis()
				
				val items = extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
				allItems += items
				
				logger.d("Kiosk $id → ${items.size} items (${t1 - t0} ms)")
			}
			
			val end = System.currentTimeMillis()
			logger.d("Merged trending count: ${allItems.size}")
			logger.d("Total time: ${end - start} ms")
			if (allItems.isNotEmpty()) {
				val listOfVideoModels = mutableListOf<YouTubeVideoDataModel>()
				for (item in allItems) {
					listOfVideoModels.add(
						YouTubeVideoDataModel(
							lastModifiedTimeDate = System.currentTimeMillis(),
							videoUrl = item.url,
							videoTitle = item.name,
							videoDescription = item.shortDescription,
							videoThumbnailUrl = item.thumbnails.firstOrNull()?.url,
							videoDuration = formatVideoDuration(item.duration),
							videoViewsCount = formatViewCounts(item.viewCount),
							videoChannelName = item.uploaderName,
							videoChannelThumbnail = item.uploaderAvatars.firstOrNull()?.url
						)
					)
				}
				listOfVideoModels
			} else {
				emptyList()
			}
		} catch (error: Exception) {
			logger.e("Trending error: ${error.message}")
			emptyList()
		}
	}
	
}