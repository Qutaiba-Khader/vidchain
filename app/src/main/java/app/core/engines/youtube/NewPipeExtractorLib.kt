package app.core.engines.youtube

import app.core.AIOApp.Companion.aioSettings
import lib.process.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.localization.*

/**
 * A singleton object that acts as a wrapper and initialization point for the NewPipe Extractor library.
 * It handles the setup of the library with the necessary configurations, such as the downloader
 * implementation and localization settings, making it ready for use throughout the application.
 */
object NewPipeExtractorLib {
	
	/**
	 * Logger for this class.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Initializes the NewPipe extractor library.
	 *
	 * This function sets up the core components required for the extractor to work,
	 * including the downloader implementation and localization settings. It retrieves the
	 * user's selected content region from the app's settings and uses it to configure
	 * the library's `Localization`.
	 */
	fun initSystem() {
		val contentRegion = aioSettings.userSelectedContentRegion
		NewPipe.init(DefaultYTDownloaderImpl(), Localization(contentRegion))
		logger.d("NewPipe initialized with region {$contentRegion}")
	}
}