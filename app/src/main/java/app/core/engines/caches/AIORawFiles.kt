package app.core.engines.caches

import app.core.AIOApp
import com.aio.R
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory.fromRawRes
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility

/**
 * Handles preloading and caching of raw Lottie animation files used across the app.
 *
 * This class ensures that animations are loaded once in the background and can be reused
 * efficiently without blocking the main thread.
 */
class AIORawFiles {

	private val logger = LogHelperUtils.from(javaClass)

	private var loadingComposition: LottieComposition? = null
	private var openActiveTasksComposition: LottieComposition? = null
	private var downloadReadyComposition: LottieComposition? = null

	/**
	 * Returns the preloaded circular loading animation composition, if available.
	 */
	fun getCircleLoadingComposition(): LottieComposition? {
		logger.d("Fetching circle loading composition: ${loadingComposition != null}")
		return loadingComposition
	}

	/**
	 * Returns the preloaded "open active tasks" animation composition, if available.
	 */
	fun getOpenActiveTasksAnimationComposition(): LottieComposition? {
		logger.d("Fetching open active tasks composition: ${openActiveTasksComposition != null}")
		return openActiveTasksComposition
	}

	/**
	 * Returns the preloaded "download ready/found" animation composition, if available.
	 */
	fun getDownloadFoundAnimationComposition(): LottieComposition? {
		logger.d("Fetching download ready composition: ${downloadReadyComposition != null}")
		return downloadReadyComposition
	}

	/**
	 * Preloads the Lottie animation compositions in a background thread.
	 *
	 * This ensures animations are ready to use immediately when requested,
	 * avoiding UI lags caused by loading animations at runtime.
	 */
	fun preloadLottieAnimation() {
		logger.d("Starting preload of Lottie animations...")

		ThreadsUtility.executeInBackground(codeBlock = {

			fromRawRes(AIOApp.INSTANCE, R.raw.animation_waiting_loading)
				.addListener { composition ->
					loadingComposition = composition
					logger.d("Loaded circle loading animation.")
				}

			fromRawRes(AIOApp.INSTANCE, R.raw.animation_active_tasks)
				.addListener { composition ->
					openActiveTasksComposition = composition
					logger.d("Loaded active tasks animation.")
				}

			fromRawRes(AIOApp.INSTANCE, R.raw.animation_videos_found)
				.addListener { composition ->
					downloadReadyComposition = composition
					logger.d("Loaded videos found animation.")
				}
		})
	}
}
