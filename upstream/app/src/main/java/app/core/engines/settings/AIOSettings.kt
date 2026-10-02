package app.core.engines.settings

import android.content.Context.*
import androidx.documentfile.provider.*
import app.core.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioDSLJsonInstance
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOLanguage.Companion.ENGLISH
import app.core.engines.fst_serializer.FSTBuilder.fstConfig
import app.core.engines.settings.AIOSettings.Companion.AIO_SETTINGS_FILE_NAME_BINARY
import app.core.engines.settings.AIOSettings.Companion.AIO_SETTINGS_FILE_NAME_JSON
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.settings.AIOSettings.Companion.convertJSONStringToClass
import com.aio.R.*
import com.anggrayudi.storage.file.*
import com.anggrayudi.storage.file.DocumentFileCompat.fromFullPath
import com.dslplatform.json.*
import io.objectbox.annotation.*
import lib.files.FileSystemUtility.isWritableFile
import lib.files.FileSystemUtility.readStringFromInternalStorage
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.*
import lib.texts.CommonTextUtils.getText
import java.io.*
import kotlin.jvm.Transient

/**
 * Manages persistent user settings and application state for the AIO application using ObjectBox.
 *
 * This class centralizes all user preferences and configurations, persisting them between
 * sessions. It leverages ObjectBox for robust, high-performance database storage, replacing
 * legacy JSON and binary file systems.
 *
 * Key Features:
 * - **Database Persistence**: Uses ObjectBox for efficient and reliable data storage.
 * - **Download Configuration**: Manages download behavior, performance, and storage settings.
 * - **Browser Preferences**: Stores settings for the in-app browser, including privacy and content options.
 * - **UI Customization**: Handles themes, language, and content region settings.
 * - **Analytics**: Tracks user interaction and engagement metrics anonymously.
 * - **Backward Compatibility**: Supports loading settings from older JSON/binary formats.
 *
 * Storage Architecture:
 * - **Primary Store**: ObjectBox database with a single entity record (ID = 1).
 * - **Backup**: Legacy JSON and binary files are maintained for compatibility but are secondary.
 * - **Thread Safety**: Operations are designed to be thread-safe for concurrent access.
 * - **Automation**: Persistence is handled automatically in the background.
 *
 * @see io.objectbox.annotation.Entity For database entity configuration.
 * @see com.dslplatform.json.CompiledJson For JSON serialization compatibility annotations.
 */
@CompiledJson
@Entity
class AIOSettings : Serializable {
	
	/**
	 * Logger instance for this settings class.
	 *
	 * Used to log events, errors, and debug information related to the lifecycle
	 * and operations of an individual `AIOSettings` instance, such as serialization,
	 * validation, and data persistence.
	 * Marked as `@Transient` to exclude it from serialization processes.
	 */
	@Transient
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * The unique identifier for the settings record in the ObjectBox database.
	 *
	 * This property serves as the primary key for the `AIOSettings` entity. It is
	 * fixed to a constant value (1) because the application maintains only a single,
	 * global instance of its settings. This singleton approach simplifies data
	 * retrieval and management, as there is always exactly one record to read or update.
	 *
	 * @see io.objectbox.annotation.Id For ObjectBox primary key configuration.
	 */
	@Id
	@JvmField
	@JsonAttribute(name = "id")
	var id: Long = 0L
	
	/**
	 * Foreign key that links the application settings to a specific download data model record.
	 *
	 * This identifier establishes a `ToOne` relationship in ObjectBox, associating this single
	 * `AIOSettings` instance with a corresponding `DownloadDataModel` entity, which likely
	in turn holds a `ToMany` relationship with individual download tasks. It ensures that the
	 * application's global settings are correctly tied to the central download management model.
	 *
	 * The value defaults to `AIOSettingsDBManager.APP_SETTINGS_DB_ID` to maintain a consistent link.
	 *
	 * @see app.core.db.downloads.DownloadDataModel
	 * @see AIOSettingsDBManager.APP_SETTINGS_DB_ID
	 */
	@JvmField
	@JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelDBId: Long = AIOSettingsDBManager.APP_SETTINGS_DB_ID
	
	/**
	 * Unique installation identifier for analytics, user tracking, and A/B testing.
	 *
	 * This ID is generated once during the application's first launch and remains
	 * constant across all subsequent sessions and updates. It allows for the anonymous
	 * tracking of user behavior and application usage patterns without collecting
	 * personally identifiable information.
	 *
	 * Key uses:
	 * - Differentiating between new and returning users.
	 * - Segmenting users for analytics and feature rollouts.
	 * - Ensuring consistency in remote configurations and experiments.
	 */
	@JvmField
	@JsonAttribute(name = "userInstallationId")
	var userInstallationId: String = ""
	
	/**
	 * A flag that tracks whether the user has successfully completed the initial,
	 * mandatory language selection process upon first launching the app.
	 *
	 * This property is checked at startup to determine if the language selection
	 * screen should be displayed.
	 *
	 * - `false` (default): The user has not yet selected a language. The app will
	 *   show the language selection UI.
	 * - `true`: The user has completed the flow. The app will bypass the language
	 *   selection screen and proceed to the main interface.
	 */
	@JvmField
	@JsonAttribute(name = "isFirstTimeLanguageSelectionComplete")
	var isFirstTimeLanguageSelectionComplete: Boolean = false
	
	/**
	 * Flag indicating whether the user has rated the application in the app store.
	 *
	 * When `true`, the application will no longer prompt the user for a rating. This state
	 * is typically set after the user successfully completes the rating flow.
	 *
	 * It is used to:
	 * - Control the frequency and visibility of in-app rating prompts.
	 * - Improve user experience by avoiding repeated requests after a rating is given.
	 * - Inform user engagement and retention strategies.
	 *
	 * Defaults to `false`.
	 */
	@JvmField
	@JsonAttribute(name = "hasUserRatedTheApplication")
	var hasUserRatedTheApplication: Boolean = false
	
	/**
	 * Flag indicating whether the user has been prompted to and has agreed to disable
	 * battery optimization for the application.
	 *
	 * This is crucial for background tasks like downloads to run uninterrupted.
	 * When `true`, it implies the app is whitelisted from Android's Doze mode
	 * and other power-saving restrictions, ensuring more reliable background operation.
	 *
	 * - `true`: User has exempted the app from battery optimization.
	 * - `false`: The app is still subject to standard battery optimization.
	 */
	@JvmField
	@JsonAttribute(name = "hasUserSkipBatteryOptimization")
	var hasUserSkipBatteryOptimization: Boolean = false
	
	/**
	 * Tracks the total number of successful download operations completed by the user.
	 *
	 * This counter is incremented each time a download finishes successfully. It serves
	 * multiple purposes:
	 * - **Analytics**: Provides insight into user activity and download frequency.
	 * - **Engagement Metrics**: Helps measure how actively the app is being used for its core function.
	 * - **Feature Unlocking**: Can be used as a threshold to unlock advanced features or rewards
	 *   for power users.
	 * - **Rate-Limiting**: May inform logic for displaying prompts, such as rating requests,
	 *   based on user engagement.
	 */
	@JvmField
	@JsonAttribute(name = "totalNumberOfSuccessfulDownloads")
	var totalNumberOfSuccessfulDownloads: Int = 0
	
	/**
	 * Stores the cumulative time, in milliseconds, that the user has spent in the application.
	 *
	 * This metric is crucial for tracking user engagement and for session management purposes.
	 * It is periodically updated to reflect the total active time since the app was first installed.
	 * The raw value is stored as a `Float` for precision and is used to derive a
	 * user-friendly formatted string in [totalUsageTimeInFormat].
	 *
	 * @see totalUsageTimeInFormat for the display-ready version of this value.
	 */
	@JvmField
	@JsonAttribute(name = "totalUsageTimeInMs")
	var totalUsageTimeInMs: Float = 0.0f
	
	/**
	 * A human-readable, formatted string representing the cumulative time the user has spent in the application.
	 *
	 * This value is derived from [totalUsageTimeInMs] and is automatically updated for display in the UI,
	 * for example, showing "1h 30m" instead of a raw millisecond count. It is primarily used for
	 * presenting user engagement statistics.
	 *
	 * @see totalUsageTimeInMs
	 */
	@JvmField
	@JsonAttribute(name = "totalUsageTimeInFormat")
	var totalUsageTimeInFormat: String = ""
	
	/**
	 * Stores the most recent text from the clipboard that has been processed.
	 *
	 * This property helps prevent the application from repeatedly processing the
	 * same clipboard content, such as when the clipboard remains unchanged between
	 * app launches or background checks. It is primarily used for features like
	 * automatic URL detection from copied text.
	 */
	@JvmField
	@JsonAttribute(name = "lastProcessedClipboardText")
	var lastProcessedClipboardText: String = ""
	
	/**
	 * Determines the default storage location for downloaded files.
	 *
	 * This setting allows the user to choose between saving files in the app's private,
	 * sandboxed storage (hidden from other apps) or a public, user-accessible directory
	 * like the system's "Downloads" or "Gallery" folder.
	 *
	 * ### Possible values:
	 * - [PRIVATE_FOLDER] (1): Saves files to the app's private internal storage. This location is secure,
	 *   not visible to other apps, and its contents are removed when the app is uninstalled.
	 * - [SYSTEM_GALLERY] (2): Saves files to a public directory (e.g., the "Downloads" folder),
	 *   making them visible in media galleries and file managers.
	 *
	 * The default value is [PRIVATE_FOLDER].
	 *
	 * @see PRIVATE_FOLDER
	 * @see SYSTEM_GALLERY
	 * @see downloadAutoFileMoveToPrivate
	 */
	@JvmField
	@JsonAttribute(name = "defaultDownloadLocation")
	var defaultDownloadLocation: Int = PRIVATE_FOLDER
	
	/**
	 * Specifies the user's chosen language for the application's user interface.
	 *
	 * This setting determines the language used for all text, labels, and other
	 * UI elements throughout the app. It is represented by a two-letter ISO 639-1
	 * language code (e.g., "en", "es", "hi").
	 *
	 * The default value is English ("en"). Changing this value will trigger a
	 * UI refresh to apply the new language.
	 *
	 * @see app.core.AIOLanguage for available language codes and constants.
	 */
	@JvmField
	@JsonAttribute(name = "userSelectedUILanguage")
	var userSelectedUILanguage: String = ENGLISH
	
	/**
	 * Specifies the user's preferred region for content localization and regional services.
	 *
	 * This setting uses a two-letter ISO 3166-1 alpha-2 country code (e.g., "IN" for India,
	 * "US" for United States, "GB" for Great Britain) to tailor the user experience.
	 * It influences:
	 * - Content recommendations and trending topics.
	 * - Availability of region-specific features or services.
	 * - Default language or dialect suggestions.
	 *
	 * Defaults to "IN".
	 */
	@JvmField
	@JsonAttribute(name = "userSelectedContentRegion")
	var userSelectedContentRegion: String = "IN"
	
	/**
	 * Controls the application's visual theme.
	 *
	 * This setting determines whether the UI is displayed in light mode, dark mode, or
	 * automatically adjusts based on the system-wide theme setting (available on Android 10+).
	 *
	 * Possible values:
	 * - `-1`: **Automatic** - The app's theme syncs with the device's system setting.
	 * - `1`: **Dark** - Forces the app to always use the dark theme.
	 * - `2`: **Light** - Forces the app to always use the light theme.
	 */
	@JvmField
	@JsonAttribute(name = "themeAppearance")
	var themeAppearance: Int = -1
	
	/**
	 * Toggles the daily content suggestion feature on the application's home screen.
	 *
	 * When `true`, the app will display personalized content recommendations, such as trending
	 * videos or articles, each day. These suggestions are tailored to the user's interests,
	 * which may be inferred from their browsing history, download patterns, and selected
	 * content region ([userSelectedContentRegion]).
	 *
	 * When `false`, this recommendation card will be hidden, providing a more streamlined
	 * user interface. Disabling this feature can also help reduce background data usage
	 * and processing.
	 *
	 * - `true`: Enable daily personalized content suggestions. (Default)
	 * - `false`: Disable daily content suggestions.
	 *
	 * @see userSelectedContentRegion
	 */
	@JvmField
	@JsonAttribute(name = "enableDailyContentSuggestion")
	var enableDailyContentSuggestion: Boolean = true
	
	/**
	 * Tracks the total number of times the user has changed the application language.
	 *
	 * This counter is incremented each time a user successfully selects a new language
	 * from the settings menu. It serves as an analytics metric to understand user
	 * engagement with localization features and to identify which languages are most
	 * frequently switched to.
	 *
	 * This data helps in prioritizing translation efforts and improving the language
	 * selection user experience.
	 *
	 * @see userSelectedUILanguage
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnLanguageChange")
	var totalClickCountOnLanguageChange: Int = 0
	
	/**
	 * Tracks the total number of times the user has interacted with media playback controls.
	 *
	 * This counter increments whenever a user plays, pauses, or seeks within a video or audio
	 * file in the app's built-in media player. It serves as an analytics metric to gauge
	 * user engagement with media content and the effectiveness of the player UI.
	 *
	 * This data helps in understanding user behavior and prioritizing improvements for the
	 * media playback experience.
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnMediaPlayback")
	var totalClickCountOnMediaPlayback: Int = 0
	
	/**
	 * Tracks the total number of times the user has accessed the "How-To" guide.
	 *
	 * This counter is used for analytics to understand user engagement with help
	 * and support features. It helps in evaluating the effectiveness of the guide
	 * and identifying if users are seeking assistance.
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnHowToGuide")
	var totalClickCountOnHowToGuide: Int = 0
	
	/**
	 * Tracks the total number of times the user has accessed the video URL editor.
	 *
	 * This counter is incremented each time the video URL editing feature is invoked,
	 * providing insights into user engagement with advanced download customization tools.
	 * It helps in analyzing how often users manually adjust video URLs before downloading.
	 *
	 * @see browserEnableVideoGrabber
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnVideoUrlEditor")
	var totalClickCountOnVideoUrlEditor: Int = 0
	
	/**
	 * Records the total number of times the user has accessed the "History"
	 * section from the home screen.
	 *
	 * This metric is used for analytics to understand user engagement with the
	 * browsing history feature.
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnHomeHistory")
	var totalClickCountOnHomeHistory: Int = 0
	
	/**
	 * Tracks the total number of times the user has accessed the bookmarks section from the home screen.
	 *
	 * This counter is incremented each time the user interacts with the "Bookmarks" entry point
	 * on the main interface. It is used for analytics to measure user engagement with the
	 * bookmarking feature.
	 *
	 * @see totalClickCountOnHomeHistory
	 * @see totalClickCountOnRecentDownloads
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnHomeBookmarks")
	var totalClickCountOnHomeBookmarks: Int = 0
	
	/**
	 * Tracks the total number of times the user has accessed the "Recent Downloads" section.
	 *
	 * This counter increments each time the user navigates to or interacts with the list
	 * of recently downloaded files. It is used for analytics to understand user engagement
	 * with post-download features and to optimize the visibility or functionality of this section.
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnRecentDownloads")
	var totalClickCountOnRecentDownloads: Int = 0
	
	/**
	 * Tracks the total number of times the user has clicked on a website's favicon
	 * on the application's home screen.
	 *
	 * This counter is used for analytics to measure user engagement with saved
	 * shortcuts and favorite sites, helping to understand which features are most popular.
	 * Each click on a favicon to launch a website increments this counter.
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnHomeFavicon")
	var totalClickCountOnHomeFavicon: Int = 0
	
	/**
	 * Tracks the total number of times the user has manually checked for an application update.
	 *
	 * This counter is incremented each time the "Check for updates" or a similar version check
	 * feature is used. It helps in understanding user engagement with the update process and
	 * can provide insights into how often users are proactively seeking new versions.
	 */
	@JvmField
	@JsonAttribute(name = "totalClickCountOnVersionCheck")
	var totalClickCountOnVersionCheck: Int = 0
	
	/**
	 * Tracks the total number of clicks on interstitial advertisements.
	 *
	 * This counter is used for analytics to measure user engagement with
	 * interstitial ads. It helps in understanding ad performance and user behavior.
	 *
	 * @see totalInterstitialImpression
	 * @see totalRewardedAdClick
	 */
	@JvmField
	@JsonAttribute(name = "totalInterstitialAdClick")
	var totalInterstitialAdClick: Int = 0
	
	/**
	 * Tracks the total number of interstitial advertisement impressions shown to the user.
	 *
	 * An "impression" is recorded each time an interstitial ad is successfully displayed
	 * on the screen. This metric, along with [totalInterstitialAdClick], is used for
	 * analytics to measure ad engagement and monetization effectiveness.
	 *
	 * @see totalInterstitialAdClick
	 * @see totalRewardedImpression
	 */
	@JvmField
	@JsonAttribute(name = "totalInterstitialImpression")
	var totalInterstitialImpression: Int = 0
	
	/**
	 * A counter for the total number of times the user has clicked on a rewarded advertisement.
	 *
	 * This metric is crucial for analytics and understanding user engagement with rewarded ad
	 * formats. Tracking clicks helps measure the effectiveness of ad placements and user
	 * willingness to interact with this type of monetization.
	 *
	 * @see totalRewardedImpression for tracking views versus clicks.
	 */
	@JvmField
	@JsonAttribute(name = "totalRewardedAdClick")
	var totalRewardedAdClick: Int = 0
	
	/**
	 * Tracks the total number of rewarded advertisement impressions shown to the user.
	 *
	 * An impression is counted each time a rewarded ad is successfully displayed. This metric
	 * is used for analytics to understand user engagement with rewarded ads and to measure
	 * ad performance. It is incremented independently of whether the user interacts with
	 * the ad or completes the rewarded action.
	 *
	 * @see totalRewardedAdClick for tracking clicks on these ads.
	 */
	@JvmField
	@JsonAttribute(name = "totalRewardedImpression")
	var totalRewardedImpression: Int = 0
	
	/**
	 * Defines the full folder path where WhatsApp statuses are stored on the device.
	 *
	 * This path points to the directory that the application monitors for new WhatsApp
	 * status files (images and videos). The value is initialized from a string resource,
	 * which may vary across different Android versions due to changes in WhatsApp's
	 * storage location.
	 *
	 * Although it is a `var`, this property is intended to be treated as a read-only
	 * value after its initial configuration at startup.
	 */
	@JvmField
	@JsonAttribute(name = "whatsAppStatusFullFolderPath")
	var whatsAppStatusFullFolderPath: String = getText(string.text_whatsapp_status_file_dir)
	
	/**
	 * Toggles between a single, aggregated progress indicator and individual progress bars for downloads.
	 *
	 * - When `true` (default), all active downloads are represented by a single, combined
	 *   progress notification and UI element. This provides a clean, minimalistic view of the
	 *   overall download progress.
	 * - When `false`, each download task displays its own separate progress bar and notification,
	 *   allowing for detailed monitoring of individual files.
	 *
	 * This setting primarily affects the user interface in the downloads screen and system notifications.
	 */
	@JvmField
	@JsonAttribute(name = "downloadSingleUIProgress")
	var downloadSingleUIProgress: Boolean = true
	
	/**
	 * Hides video thumbnails in download lists for improved privacy.
	 *
	 * When set to `true`, the application will display a generic placeholder icon instead of
	 * a video's thumbnail image in the list of downloads. This is useful for users who want to
	 * maintain privacy, especially in shared or public environments, by preventing others from
	 * seeing a preview of their downloaded content.
	 *
	 * - `true`: Video thumbnails are hidden and replaced with a generic icon.
	 * - `false`: Video thumbnails are displayed as usual. (Default)
	 */
	@JvmField
	@JsonAttribute(name = "downloadHideVideoThumbnail")
	var downloadHideVideoThumbnail: Boolean = false
	
	/**
	 * Controls whether a notification sound is played upon successful download completion.
	 *
	 * When `true` (the default), the application will play the system's default notification
	 * sound to alert the user that a file has finished downloading. This provides immediate
	 * audible feedback, which is useful when the app is in the background.
	 *
	 * When `false`, downloads will complete silently without any sound. This can be desirable
	 * for users who prefer fewer interruptions or are downloading many files in succession.
	 *
	 * @see downloadHideNotification
	 */
	@JvmField
	@JsonAttribute(name = "downloadPlayNotificationSound")
	var downloadPlayNotificationSound: Boolean = true
	
	/**
	 * Hides system notifications for download operations.
	 *
	 * When `true`, all notifications related to download progress, completion, or errors
	 * will be suppressed from the system's notification tray. This provides a less intrusive
	 * experience, especially for frequent or background downloads. The download status
	 * will still be visible within the app's UI.
	 *
	 * When `false` (default), the app will display standard system notifications to keep
	 * the user informed about the status of their downloads.
	 *
	 * - `true`: Suppress all download-related system notifications.
	 * - `false`: Show system notifications for download progress and status.
	 *
	 * @see downloadPlayNotificationSound to control completion sounds.
	 */
	@JvmField
	@JsonAttribute(name = "downloadHideNotification")
	var downloadHideNotification: Boolean = false
	
	/**
	 * Hides the download progress bar and related UI elements from the main interface.
	 *
	 * When set to `true`, the real-time progress indicators for ongoing downloads
	 * (e.g., progress bars, percentage text) will be hidden from primary UI screens,
	 * offering a cleaner and more minimalistic view. Notifications may still show
	 * progress, depending on the [downloadHideNotification] setting.
	 *
	 * When `false` (default), download progress is visible in the UI, providing users
	 * with immediate feedback on their downloads.
	 *
	 * - `true`: Conceal download progress from the main UI.
	 * - `false`: Show download progress in the main UI.
	 *
	 * @see downloadHideNotification
	 * @see downloadSingleUIProgress
	 */
	@JvmField
	@JsonAttribute(name = "hideDownloadProgressFromUI")
	var hideDownloadProgressFromUI: Boolean = false
	
	/**
	 * Enables automatic removal of completed download tasks from the download list.
	 *
	 * When set to `true`, tasks that have finished successfully will be automatically
	 * removed from the UI after a specific period, helping to keep the download list clean.
	 * The duration after which a task is removed is defined by [downloadAutoRemoveTaskAfterNDays].
	 *
	 * If set to `false` (default), completed tasks will remain in the list until
	 * manually removed by the user.
	 *
	 * @see downloadAutoRemoveTaskAfterNDays
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoRemoveTasks")
	var downloadAutoRemoveTasks: Boolean = false
	
	/**
	 * Specifies the number of days after which a completed download task is
	 * automatically removed from the download list.
	 *
	 * This setting is only active when [downloadAutoRemoveTasks] is enabled.
	 * A value of `0` means that tasks will be removed immediately upon completion.
	 * Any positive integer (`n`) will cause tasks to be kept for `n` days before
	 * being automatically cleared.
	 *
	 * - **`0`**: Remove immediately after completion.
	 * - **`> 0`**: Remove after the specified number of days.
	 *
	 * @see downloadAutoRemoveTasks
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoRemoveTaskAfterNDays")
	var downloadAutoRemoveTaskAfterNDays: Int = 0
	
	/**
	 * Determines the user interaction required to open a downloaded file from the download list.
	 *
	 * When `true` (default), a single tap on a completed download item will open the file.
	 * This provides quick and intuitive access.
	 *
	 * When `false`, the user must perform a long press on the item to reveal the "Open" action.
	 * This can help prevent accidental openings and allows the single-tap action to be used for
	 * selecting multiple files in a future update.
	 *
	 * - `true`: Open on single click.
	 * - `false`: Open on long press.
	 */
	@JvmField
	@JsonAttribute(name = "openDownloadedFileOnSingleClick")
	var openDownloadedFileOnSingleClick: Boolean = true
	
	/**
	 * Toggles automatic resumption of downloads that are interrupted due to network issues,
	 * app closures, or other failures.
	 *
	 * When `true` (default), the download manager will automatically attempt to restart
	 * failed or paused downloads from the point of interruption once network connectivity
	 * is restored or the app is relaunched. This provides a more robust and seamless
	 * user experience.
	 *
	 * When `false`, interrupted downloads must be manually resumed by the user.
	 *
	 * - `true`: Automatically resume interrupted downloads.
	 * - `false`: Require manual intervention to resume downloads.
	 *
	 * @see downloadAutoResumeMaxErrors to configure the maximum number of resume attempts.
	 * @see downloadWifiOnly which can affect when resumption occurs.
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoResume")
	var downloadAutoResume: Boolean = true
	
	/**
	 * Specifies the maximum number of consecutive errors a download can encounter before
	 * auto-resume attempts are permanently stopped for that task.
	 *
	 * This setting works in conjunction with [downloadAutoResume]. When an active download
	 * fails due to a network error, timeout, or server issue, the auto-resume feature
	 * will automatically try to restart it. This property defines a tolerance threshold
	 * to prevent a failing download from retrying indefinitely, which could waste
	 * battery and data.
	 *
	 * Once the error count for a specific download reaches this limit, it will be marked
	 * as permanently failed and will require manual user intervention to restart.
	 *
	 * - **Default**: 35 attempts.
	 *
	 * @see downloadAutoResume
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoResumeMaxErrors")
	var downloadAutoResumeMaxErrors: Int = 35
	
	/**
	 * Controls whether the download engine should automatically follow HTTP/HTTPS redirects.
	 *
	 * When `true` (default), if a download URL returns a redirect status code (e.g., 301, 302, 307),
	 * the downloader will automatically follow the new URL provided in the `Location` header. This is
	 * essential for handling shortened links or resources that have been moved.
	 *
	 * When `false`, the download will fail if a redirect is encountered. Disabling this may be
	 * useful for debugging or security purposes to prevent unexpected redirects to malicious sites,
	 * but it can break downloads from many common sources.
	 *
	 * - `true`: Follow URL redirects automatically.
	 * - `false`: Do not follow redirects; fail the download instead.
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoLinkRedirection")
	var downloadAutoLinkRedirection: Boolean = true
	
	/**
	 * Toggles automatic organization of downloads into category-based folders.
	 *
	 * When `true` (default), the application will automatically create subdirectories
	 * within the main download location (e.g., "Videos", "Music", "Images") and
	 * place downloaded files into the appropriate folder based on their file type.
	 * This helps keep downloads organized and easy to find.
	 *
	 * When `false`, all files are downloaded directly into the root of the selected
	 * download directory without any sub-folder organization.
	 *
	 * - `true`: Organize downloads into folders like "Videos", "Music", etc.
	 * - `false`: Save all downloads in the top-level download folder.
	 *
	 * @see defaultDownloadLocation
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoFolderCatalog")
	var downloadAutoFolderCatalog: Boolean = true
	
	/**
	 * Toggles automatic thread selection for parallel downloads.
	 *
	 * When `true` (default), the download engine dynamically determines the optimal number of threads
	 * to use for a download based on factors like network speed, file size, and server support. This
	 * can significantly improve download speeds by maximizing network throughput.
	 *
	 * When `false`, the number of threads is fixed to the value specified in
	 * [downloadDefaultThreadConnections]. Disabling this may be useful for troubleshooting or
	 * to limit resource usage on constrained networks or devices.
	 *
	 * @see downloadDefaultThreadConnections
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoThreadSelection")
	var downloadAutoThreadSelection: Boolean = true
	
	/**
	 * Automatically moves successfully downloaded files to the app's private storage.
	 *
	 * When `true`, any file downloaded to a public location (like the system's "Downloads" folder)
	 * will be moved to the app's sandboxed private directory upon completion. This is useful
	 * for users who want to keep their downloads organized and hidden from other apps, such as galleries
	 * or file managers.
	 *
	 * This setting works in conjunction with [privateFolderPassword] to secure access to these files.
	 *
	 * - `true`: Move completed downloads to private storage.
	 * - `false`: Leave completed downloads in their original public download location.
	 *
	 * @see defaultDownloadLocation
	 * @see privateFolderPassword
	 * @see PRIVATE_FOLDER
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoFileMoveToPrivate")
	var downloadAutoFileMoveToPrivate: Boolean = false
	
	/**
	 * Automatically converts downloaded video files into MP3 audio files upon successful download.
	 *
	 * When `true`, the application will post-process downloaded videos, extracting the audio track
	 * and saving it as an MP3 file. The original video file may be kept or deleted depending on
	 * other application settings. This feature is useful for users who only want the audio
	 * from a video, such as music or podcasts.
	 *
	 * When `false` (default), videos are saved in their original format without any conversion.
	 *
	 * **Note**: This process requires additional processing time and CPU resources after the
	 * download completes. Conversion may fail if the video format is unsupported or the file is corrupt.
	 */
	@JvmField
	@JsonAttribute(name = "downloadAutoConvertVideosToMp3")
	var downloadAutoConvertVideosToMp3: Boolean = false
	
	/**
	 * Specifies the size of the memory buffer used during download operations, in bytes.
	 *
	 * This buffer holds data chunks as they are read from the network before being written to disk.
	 * A larger buffer size can improve performance on fast connections by reducing the number of
	 * disk write operations, but it also consumes more memory. A smaller size is more memory-efficient
	 * but may lead to slower download speeds due to more frequent I/O waits.
	 *
	 * The default value is 8192 bytes (8 KB), which offers a good balance between performance
	 * and memory usage for typical mobile network conditions.
	 *
	 * @see downloadMaxHttpReadingTimeout
	 * @see downloadDefaultThreadConnections
	 */
	@JvmField
	@JsonAttribute(name = "downloadBufferSize")
	var downloadBufferSize: Int = 1024 * 8
	
	/**
	 * Specifies the maximum time in milliseconds to wait for data to be read from the server
	 * during an HTTP download. If no data is received within this period, the connection will time out.
	 *
	 * A longer timeout can help on slow or unstable networks but may cause downloads to hang
	 * for an extended period if the server is unresponsive. A shorter timeout fails faster
	 * but may cause issues on high-latency connections.
	 *
	 * The default value is 30,000 milliseconds (30 seconds).
	 */
	@JvmField
	@JsonAttribute(name = "downloadMaxHttpReadingTimeout")
	var downloadMaxHttpReadingTimeout: Int = 1000 * 30
	
	/**
	 * Default number of concurrent connections (threads) to use for a single download.
	 *
	 * A higher value can increase download speed by splitting the file into multiple parts
	 * and downloading them simultaneously. However, this also increases CPU and network
	 * resource usage and may not be supported by all servers.
	 *
	 * A value of `1` disables multi-threaded downloading, processing the download in a single stream.
	 *
	 * @see downloadAutoThreadSelection for automatic optimization of this value.
	 * @see downloadDefaultParallelConnections for the number of separate files to download at once.
	 */
	@JvmField
	@JsonAttribute(name = "downloadDefaultThreadConnections")
	var downloadDefaultThreadConnections: Int = 1
	
	/**
	 * Default number of concurrent connections for parallel downloads.
	 *
	 * This setting controls how many separate download threads can run simultaneously for a single file,
	 * which can significantly increase download speed by fetching different parts of the file at once.
	 * It is used when [downloadAutoThreadSelection] is disabled or as a fallback.
	 *
	 * A higher value may improve speed but also increases network and CPU usage.
	 * A value of `1` effectively disables parallel downloading for a task.
	 *
	 * @see numberOfMaxDownloadThreshold which limits the total number of separate download tasks.
	 * @see downloadDefaultThreadConnections for a related, but distinct, thread setting.
	 */
	@JvmField
	@JsonAttribute(name = "downloadDefaultParallelConnections")
	var downloadDefaultParallelConnections: Int = 10
	
	/**
	 * Toggles checksum verification for downloaded files to ensure data integrity.
	 *
	 * When set to `true`, the application will calculate and compare a checksum (e.g., MD5, SHA-1)
	 * for the downloaded file against a checksum provided by the server, if available. This
	 * helps detect file corruption that may occur during transmission.
	 *
	 * Enabling this option may add a small amount of overhead to the download process
	 * but provides a higher guarantee of file integrity. It is recommended for critical
	 * downloads where data corruption cannot be tolerated.
	 *
	 * - `true`: Verify checksums to ensure file integrity.
	 * - `false`: Skip checksum verification for faster downloads. (Default)
	 */
	@JvmField
	@JsonAttribute(name = "downloadVerifyChecksum")
	var downloadVerifyChecksum: Boolean = false
	
	/**
	 * Sets the maximum download speed to limit network bandwidth usage.
	 *
	 * This value represents the speed limit in bytes per second. When set to a positive
	 * number, the application will throttle download speeds to not exceed this rate.
	 *
	 * A value of `0` (the default) indicates that there is no speed limit, and downloads
	 * will use the maximum available bandwidth. This is useful for managing data consumption
	 * or ensuring other network activities remain responsive.
	 *
	 * - **Value**: Speed in bytes per second.
	 * - **`0`**: Unlimited speed.
	 * - **Example**: `1048576` would limit speed to 1 MB/s.
	 */
	@JvmField
	@JsonAttribute(name = "downloadMaxNetworkSpeed")
	var downloadMaxNetworkSpeed: Long = 0
	
	/**
	 * Restricts all download operations to Wi-Fi networks only.
	 *
	 * When set to `true`, the download manager will pause any active or pending
	 * downloads if the device is not connected to a Wi-Fi network. Downloads will
	 * automatically resume once a Wi-Fi connection is re-established.
	 * This helps users conserve mobile data.
	 *
	 * When `false` (default), downloads can proceed over any available network
	 * connection, including cellular data.
	 *
	 * - `true`: Downloads are only permitted on Wi-Fi.
	 * - `false`: Downloads can use any network (Wi-Fi or cellular).
	 */
	@JvmField
	@JsonAttribute(name = "downloadWifiOnly")
	var downloadWifiOnly: Boolean = false
	
	/**
	 * The HTTP User-Agent string used for download requests.
	 *
	 * This string identifies the application to the web server when initiating a download.
	 * Customizing the User-Agent can be useful to bypass server-side restrictions that
	 * block or throttle downloads from unknown clients. Some servers may only serve
	 * content to clients that appear to be standard web browsers.
	 *
	 * The default value is a generic User-Agent, but it can be modified by the user
	 * in the advanced settings to mimic a different browser or device.
	 *
	 * @see browserHttpUserAgent for the in-app browser's separate User-Agent.
	 */
	@JvmField
	@JsonAttribute(name = "downloadHttpUserAgent")
	var downloadHttpUserAgent: String = getText(string.text_downloads_default_http_user_agent)
	
	/**
	 * Specifies the HTTP proxy server for all download requests.
	 *
	 * This setting allows users to route download traffic through a proxy, which can be useful
	 * for bypassing network restrictions or enhancing privacy.
	 *
	 * - **Format**: The value should be in the format `[scheme]://[username:password@]host:port`.
	 *   - `scheme`: Can be `http`, `https`, or `socks` (e.g., `socks5`).
	 *   - `username:password@`: Optional credentials for proxy authentication.
	 *   - Examples: `http://192.168.1.1:8080`, `socks://user:pass@proxy.example.com:1080`.
	 * - **Usage**: If the string is empty (default), no proxy is used.
	 *
	 * @see downloadHttpUserAgent
	 */
	@JvmField
	@JsonAttribute(name = "downloadHttpProxyServer")
	var downloadHttpProxyServer: String = ""
	
	/**
	 * A flag that indicates whether the app terminated unexpectedly during the last session.
	 *
	 * This value is set to `true` by a crash handler just before the app shuts down due to an
	 * unhandled exception. On the next launch, the app can check this flag to perform recovery
	 * actions, such as clearing caches, displaying a warning to the user, or logging detailed
	 * diagnostic data. It is automatically reset to `false` after being read to ensure it only
	 * applies to the immediately preceding session.
	 *
	 * @see UncaughtExceptionHandler for implementation details.
	 */
	@JvmField
	@JsonAttribute(name = "hasAppCrashedRecently")
	var hasAppCrashedRecently: Boolean = false
	
	/**
	 * A user-defined password to protect access to the application's private folder.
	 *
	 * This password is used to encrypt and secure files stored within the app's
	 * sandboxed storage, preventing unauthorized access to downloaded media.
	 * An empty string signifies that the private folder is not password-protected.
	 *
	 * @see PRIVATE_FOLDER for the related storage location setting.
	 */
	@JvmField
	@JsonAttribute(name = "privateFolderPassword")
	var privateFolderPassword: String = ""
	
	/**
	 * Defines the maximum number of concurrent downloads permitted. This setting
	 * acts as a rate-limiting mechanism to manage network and system resource usage.
	 *
	 * When the number of active downloads reaches this threshold, new download
	 * requests will be queued until an existing one completes. A value of `1`
	 * enforces sequential downloading.
	 *
	 * @see downloadDefaultParallelConnections for a related performance setting.
	 */
	@JvmField
	@JsonAttribute(name = "numberOfMaxDownloadThreshold")
	var numberOfMaxDownloadThreshold: Int = 1
	
	/**
	 * Tracks the number of downloads initiated by the user. This counter is used
	 * for rate-limiting purposes in conjunction with [numberOfMaxDownloadThreshold].
	 * It helps manage download frequency and prevent abuse. The value is reset
	 * periodically or based on specific application logic.
	 *
	 * @see numberOfMaxDownloadThreshold
	 */
	@JvmField
	@JsonAttribute(name = "numberOfDownloadsUserDid")
	var numberOfDownloadsUserDid: Int = 0
	
	/**
	 * Sets the default homepage URL for the in-app browser.
	 * This URL is loaded whenever a new browser tab is opened or when the user
	 * taps the home button. It defaults to "https://www.google.com".
	 */
	@JvmField
	@JsonAttribute(name = "browserDefaultHomepage")
	var browserDefaultHomepage: String = getText(string.text_https_google_com)
	
	/**
	 * Toggles the in-app browser's user agent to request desktop versions of websites.
	 * When `true`, the browser mimics a desktop client, often providing a more feature-rich
	 * browsing experience. When `false` (default), it requests mobile-optimized versions.
	 */
	@JvmField
	@JsonAttribute(name = "browserDesktopBrowsing")
	var browserDesktopBrowsing: Boolean = false
	
	/**
	 * Enables or disables the built-in ad-blocking functionality within the in-app browser.
	 *
	 * When set to `true`, the browser will attempt to block known ad-serving domains and scripts,
	 * leading to a cleaner browsing experience and potentially faster page loads.
	 * When `false`, all content, including advertisements, is loaded.
	 *
	 * Defaults to `true`.
	 */
	@JvmField
	@JsonAttribute(name = "browserEnableAdblocker")
	var browserEnableAdblocker: Boolean = true
	
	/**
	 * Enables or disables JavaScript execution within the in-app browser.
	 *
	 * When enabled (`true`), web pages can run scripts, which is necessary for most modern,
	 * interactive websites to function correctly. Disabling it (`false`) can improve
	 * performance and security by preventing scripts from running, but may cause some
	 * websites to break or display incorrectly.
	 *
	 * Defaults to `true`.
	 */
	@JvmField
	@JsonAttribute(name = "browserEnableJavascript")
	var browserEnableJavascript: Boolean = true
	
	/**
	 * Controls whether images are loaded in the in-app browser.
	 *
	 * When set to `true` (default), web pages will render with all images, providing
	 * a full visual experience. When set to `false`, image loading is disabled. This can
	 * significantly reduce data usage and speed up page load times, especially on slow
	 * network connections. Disabling images may affect the layout and usability of some websites.
	 *
	 * - `true`: Images are loaded.
	 * - `false`: Images are blocked.
	 *
	 * @see browserEnableJavascript
	 * @see browserEnableAdblocker
	 */
	@JvmField
	@JsonAttribute(name = "browserEnableImages")
	var browserEnableImages: Boolean = true
	
	/**
	 * Controls whether the in-app browser should block popup windows.
	 *
	 * When `true`, the browser will attempt to prevent new windows or tabs
	 * from being opened by websites, which is useful for blocking intrusive
	 * ads and unwanted redirects. When `false`, popups are allowed.
	 *
	 * Defaults to `true`.
	 */
	@JvmField
	@JsonAttribute(name = "browserEnablePopupBlocker")
	var browserEnablePopupBlocker: Boolean = true
	
	/**
	 * Enables or disables the video grabber feature in the in-app browser.
	 *
	 * When enabled, the browser will attempt to detect and make available for download
	 * any streaming videos on the current webpage. Disabling this can improve browsing
	 * performance on video-heavy sites but will prevent the app from offering video
	 * download options.
	 *
	 * Defaults to `true`.
	 */
	@JvmField
	@JsonAttribute(name = "browserEnableVideoGrabber")
	var browserEnableVideoGrabber: Boolean = true
	
	/**
	 * The HTTP User-Agent string sent with requests made by the in-app browser.
	 *
	 * This string identifies the application to web servers. It can be customized by the user
	 * to mimic different browsers or devices (e.g., a desktop browser), which can influence
	 * how websites render their content. The default value is a standard mobile browser agent.
	 *
	 * @see browserDesktopBrowsing to toggle between mobile and desktop views.
	 */
	@JvmField
	@JsonAttribute(name = "browserHttpUserAgent")
	var browserHttpUserAgent: String = getText(string.text_browser_default_mobile_http_user_agent)
	
	/**
	 * Initializes the settings-loading process from internal storage.
	 *
	 * This function schedules a background task to load application settings. It serves as the
	 * public entry point for the settings restoration mechanism, which handles legacy data formats
	 * (binary and JSON) to ensure backward compatibility during app startup. The actual loading
	 * logic, including the fallback mechanism, is implemented in the private `initializeLegacyDataParser`
	 * method.
	 *
	 * The loading process prioritizes the binary format for performance and falls back to JSON if
	 * the binary data is missing, corrupted, or invalid.
	 *
	 * @see initializeLegacyDataParser for the detailed implementation of the loading logic.
	 */
	fun readObjectFromStorage() {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 1200,
			codeBlock = {
				initializeLegacyDataParser()
			})
	}
	
	/**
	 * Persists the current settings to storage.
	 *
	 * This method saves the `AIOSettings` object to multiple storage mediums to ensure
	 * data integrity and provide robust backup options. The operation is performed
	 * asynchronously to avoid blocking the main thread.
	 *
	 * Persistence Strategy:
	 * 1.  **ObjectBox Database**: Saves the settings to the primary [AIOSettingsDBManager] database
	 *     for fast, reliable, and structured access.
	 * 2.  **Binary File**: A compact, optimized binary representation (`.dat`) is saved for quick
	 *     legacy restoration if needed.
	 * 3.  **JSON File**: A human-readable JSON (`.json`) version is also saved, which is useful
	 *     for debugging, manual inspection, and backward compatibility.
	 *
	 * All save operations are executed within a single background task and are protected by
	 * a try-catch block to handle potential I/O errors gracefully.
	 *
	 * @see AIOSettingsDBManager.saveSettingsInDB
	 * @see saveToBinary
	 * @see convertClassToJSON
	 */
	@Synchronized
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Updating settings in storage")
				
				// Save optimized binary version
				saveToBinary(fileName = AIO_SETTINGS_FILE_NAME_BINARY)
				
				// Save readable JSON version
				saveStringToInternalStorage(
					fileName = AIO_SETTINGS_FILE_NAME_JSON,
					data = convertClassToJSON()
				)
				AIOSettingsDBManager.saveSettingsInDB(settings = this)
				logger.d("Settings successfully updated in storage")
			} catch (error: Exception) {
				logger.e("Error updating settings in storage: ${error.message}", error)
			}
		})
	}
	
	/**
	 * Serializes the current `AIOSettings` instance into a JSON string.
	 *
	 * This function uses the application's shared DSL-JSON serializer (`aioDSLJsonInstance`)
	 * for high-performance serialization. The process involves writing the object's data
	 * into an in-memory byte stream and then converting that stream into a UTF-8 encoded string.
	 *
	 * This method is primarily used for:
	 * - Persisting the application's configuration to a human-readable file (`aio_settings.json`),
	 *   serving as a backup or for debugging.
	 * - Exporting settings data for diagnostic purposes.
	 *
	 * @return A JSON string representation of the current `AIOSettings` object.
	 * @see updateInStorage where this function is called to save the JSON file.
	 * @see AIOApp.aioDSLJsonInstance for the JSON serializer instance.
	 * @see convertJSONStringToClass for the corresponding deserialization logic.
	 */
	fun convertClassToJSON(): String {
		val jsonOutputStream = ByteArrayOutputStream(16 * 1024)
		logger.d("Converting settings to JSON")
		jsonOutputStream.reset()
		aioDSLJsonInstance.serialize(this, jsonOutputStream)
		return jsonOutputStream.toString(Charsets.UTF_8.name())
	}
	
	/**
	 * Validates that the user-selected download folder is writable and accessible.
	 *
	 * This function ensures the application can write to the configured download directory,
	 * preventing I/O errors during file operations. If the folder is found to be invalid
	 * (e.g., deleted or permissions revoked), it automatically reverts to a default,
	 * ensuring a stable download location.
	 *
	 * ### Workflow:
	 * 1.  **Retrieve Current Folder**: Gets the `DocumentFile` for the user's currently
	 *     configured download directory using [getUserSelectedDir].
	 * 2.  **Check Writability**: Verifies if the folder is still writable.
	 * 3.  **Handle Invalid Folder**: If the folder is not writable or inaccessible, it
	 *     logs the issue and automatically creates a default "AIO" folder in the
	 *     public `Downloads` directory as a fallback.
	 * 4.  **Persist Changes**: Saves the potentially updated configuration to ensure
	 *     the change persists across app sessions.
	 *
	 * This robust handling prevents crashes and ensures a reliable download experience
	 * even if the user's storage configuration changes externally.
	 *
	 * @see getUserSelectedDir
	 * @see createDefaultAIODownloadFolder
	 * @see updateInStorage
	 */
	fun validateUserSelectedFolder() {
		logger.d("Validating user selected folder")
		
		// Check whether the currently configured folder is writable
		if (!isWritableFile(getUserSelectedDir())) {
			logger.d("User selected folder not writable, creating default folder")
			
			// If folder is invalid or inaccessible, revert to default AIO folder
			createDefaultAIODownloadFolder(onCreateDir = {
				// Persist updated folder info in settings (e.g., JSON or binary file)
				aioSettings.updateInStorage()
			})
		}
	}
	
	/**
	 * Initializes settings by parsing legacy data formats from internal storage.
	 *
	 * This function is responsible for ensuring backward compatibility by attempting to load settings
	 * from older, file-based storage formats (binary and JSON) before the migration to ObjectBox.
	 * It provides a seamless upgrade path for existing users by migrating their old settings
	 * into the new database-backed system.
	 *
	 * The loading process follows a specific priority:
	 * 1.  **Binary First**: It first attempts to load settings from a `.dat` file. This format is
	 *     faster to parse and was the primary method in previous versions.
	 * 2.  **JSON Fallback**: If the binary file is missing, corrupted, or fails to load, the function
	 *     falls back to parsing a `.json` file. This provides a robust recovery mechanism.
	 *
	 * Upon successfully loading settings from either format, the data is immediately saved to the
	 * new persistent storage (ObjectBox) via `updateInStorage()`, and the user's selected download
	 * folder is validated to ensure it remains accessible. If both loading methods fail, the
	 * application will proceed with default settings.
	 *
	 * @see loadFromBinary
	 * @see convertJSONStringToClass
	 * @see updateInStorage
	 * @see validateUserSelectedFolder
	 */
	private fun initializeLegacyDataParser() {
		try {
			var isBinaryFileValid = false
			
			// Retrieve reference to the internal data folder
			val internalDir = AIOApp.internalDataFolder
			
			// Attempt to locate the binary settings file within internal storage
			val settingsBinaryDataFile = internalDir.findFile(AIO_SETTINGS_FILE_NAME_BINARY)
			
			// STEP 1: Try to restore from binary settings file
			if (settingsBinaryDataFile != null && settingsBinaryDataFile.exists()) {
				logger.d("Found binary settings file, attempting to load")
				
				// Get absolute path and read binary contents
				val absolutePath = settingsBinaryDataFile.getAbsolutePath(INSTANCE)
				val objectInMemory = loadFromBinary(File(absolutePath))
				
				// Validate and apply loaded settings
				if (objectInMemory != null) {
					logger.d("Successfully loaded settings from binary format")
					
					// Assign loaded data to the global settings instance
					aioSettings = objectInMemory
					
					// Update persistent storage and validate directory access
					aioSettings.updateInStorage()
					validateUserSelectedFolder()
					
					isBinaryFileValid = true
				} else {
					logger.d("Failed to load settings from binary format")
				}
			}
			
			// STEP 2: Fallback to JSON format if binary load fails
			if (!isBinaryFileValid) {
				logger.d("Attempting to load settings from JSON format")
				
				readStringFromInternalStorage(AIO_SETTINGS_FILE_NAME_JSON).let { jsonString ->
					if (jsonString.isNotEmpty()) {
						// Deserialize JSON into AIOSettings object
						convertJSONStringToClass(jsonString = jsonString).let {
							logger.d("Successfully loaded settings from JSON format")
							
							// Assign loaded JSON data to the global instance
							aioSettings = it
							
							// Update persistent cache and validate folder access
							aioSettings.updateInStorage()
							validateUserSelectedFolder()
						}
					} else {
						logger.d("No JSON settings found or file empty")
					}
				}
			}
			
		} catch (error: Exception) {
			logger.e("Error reading settings from storage: ${error.message}", error)
		}
	}
	
	/**
	 * Serializes and writes the current settings object to a binary file in the app's internal storage.
	 *
	 * This method provides a high-performance way to persist the application settings.
	 * The binary format is faster to read and write compared to JSON, making it ideal for
	 * frequent updates and quick app startup.
	 *
	 * ### Workflow
	 * 1.  Uses [fstConfig] for fast object serialization.
	 * 2.  Opens a `FileOutputStream` in **`MODE_PRIVATE`**, which creates the file or
	 *     replaces it if it already exists.
	 * 3.  Converts the `AIOSettings` instance into a byte array.
	 * 4.  Writes the byte array to the specified file.
	 * 5.  The stream is closed automatically by the `use` block, ensuring resources are freed.
	 *
	 * ### Thread Safety
	 * This function is annotated with `@Synchronized` to ensure that only one thread can
	 * write to the settings file at a time, preventing data corruption from concurrent access.
	 *
	 * @param fileName The name of the binary file to create or overwrite (e.g., `"aio_settings.dat"`).
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving settings to binary file: $fileName")
			
			// Open or create binary file inside app’s internal storage
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			
			fileOutputStream.use { fos ->
				// Serialize current object instance to binary format
				val bytes = fstConfig.asByteArray(this)
				
				// Write binary data to file
				fos.write(bytes)
				logger.d("Binary settings saved successfully")
			}
		} catch (error: Exception) {
			logger.e("Error saving binary settings: ${error.message}", error)
		}
	}
	
	/**
	 * Loads and deserializes AIO settings from a binary file.
	 *
	 * This function serves as a fast-loading alternative to JSON parsing, restoring the
	 * application's configuration from a compact binary format.
	 *
	 * The process involves the following steps:
	 * 1.  It first checks if the provided `settingDataBinaryFile` exists. If not, it returns `null`.
	 * 2.  If the file exists, it reads all bytes from the file.
	 * 3.  It then attempts to deserialize these bytes back into an [AIOSettings] object using FST.
	 * 4.  If deserialization is successful, the resulting object is returned.
	 * 5.  If any exception occurs during reading or deserialization (e.g., file corruption),
	 *     the corrupted file is automatically deleted to prevent future loading errors, and `null` is returned.
	 *
	 * @param settingDataBinaryFile The [File] object pointing to the stored binary settings data.
	 * @return A deserialized [AIOSettings] object on success, or `null` if the file does not exist,
	 *         is corrupted, or another error occurs.
	 */
	private fun loadFromBinary(settingDataBinaryFile: File): AIOSettings? {
		if (!settingDataBinaryFile.exists()) {
			logger.d("Binary settings file does not exist")
			return null
		}
		
		return try {
			logger.d("Loading settings from binary file")
			
			// Read all bytes and reconstruct the settings object
			val bytes = settingDataBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("Successfully loaded settings from binary file")
			} as AIOSettings
		} catch (error: Exception) {
			logger.e("Error loading binary settings: ${error.message}", error)
			
			// Delete corrupted settings file to avoid repeated failures
			settingDataBinaryFile.delete()
			null
		}
	}
	
	/**
	 * Resolves the user-selected download directory into a writable [DocumentFile].
	 *
	 * This function determines the target storage location based on the `defaultDownloadLocation`
	 * preference and returns a `DocumentFile` handle for file operations. It supports both
	 * private internal storage and public external directories, ensuring compatibility with
	 * modern Android storage permissions (Scoped Storage).
	 *
	 * It handles the following cases:
	 * - **[PRIVATE_FOLDER]**: Returns a `DocumentFile` pointing to the app's internal,
	 *   private data directory. This location is secure and only accessible by the app.
	 * - **[SYSTEM_GALLERY]**: Returns a `DocumentFile` for the public system gallery
	 *   (e.g., "Downloads" or a custom media folder), making files visible to the user and other apps.
	 * - **Other/Invalid**: Returns `null` if the preference is set to an unknown value.
	 *
	 * The underlying `fromFullPath()` utility ensures that the correct permissions and
	 * storage frameworks are used to access the file path.
	 *
	 * @return A writable [DocumentFile] representing the selected directory, or `null` if the
	 *         configured location is invalid or unsupported.
	 */
	private fun getUserSelectedDir(): DocumentFile? {
		return when (aioSettings.defaultDownloadLocation) {
			PRIVATE_FOLDER -> {
				logger.d("Getting private folder directory")
				
				// Internal app data directory (private to the application)
				val internalDataFolderPath = INSTANCE.dataDir.absolutePath
				
				// Convert to DocumentFile for safe file access
				fromFullPath(
					context = INSTANCE,
					fullPath = internalDataFolderPath,
					requiresWriteAccess = true
				)
			}
			
			SYSTEM_GALLERY -> {
				logger.d("Getting system gallery directory")
				
				// Retrieve localized path for system gallery folder
				val resID = string.text_default_aio_download_folder_path
				val externalDataFolderPath = getText(resID)
				
				// Convert to DocumentFile representing the public gallery path
				fromFullPath(
					context = INSTANCE,
					fullPath = externalDataFolderPath,
					requiresWriteAccess = true
				)
			}
			
			else -> {
				// Unknown or unsupported download location type
				logger.d("Unknown download location type")
				null
			}
		}
	}
	
	/**
	 * Creates a default "AIO" download folder in the public `Downloads` directory as a fallback.
	 *
	 * This function is invoked when the application detects that the user's currently configured
	 * download location is invalid, inaccessible, or no longer writable (e.g., the folder was
	 * deleted or its permissions were revoked). It ensures that there is always a valid directory
	 * for saving downloads, preventing I/O errors and application failures.
	 *
	 * ### Key Actions:
	 * 1.  **Get Default Name**: Retrieves a localized folder name (e.g., "AIO") from string resources.
	 * 2.  **Create Folder**: Attempts to create a new directory with that name inside the device's
	 *     public `Downloads` directory using the Storage Access Framework.
	 * 3.  **Error Handling**: Catches and logs any exceptions that occur during folder creation,
	 *     such as permission denials or I/O issues, to prevent the app from crashing.
	 *
	 * This method is a critical part of the storage validation and recovery process.
	 *
	 * @see validateUserSelectedFolder which calls this function when validation fails.
	 */
	private fun createDefaultAIODownloadFolder(onCreateDir: (Boolean) -> Unit = {}) {
		try {
			logger.d("Creating default AIO download folder")
			
			// Retrieve default folder name from localized strings (e.g., "AIO Downloads")
			val defaultFolderName = getText(string.title_default_application_folder)
			
			// Attempt to create directory inside the public download folder
			INSTANCE.getPublicDownloadDir()?.createDirectory(defaultFolderName)?.let {
				if (it.exists()) {
					onCreateDir.invoke(true)
				}
			}
			// Log success if no exception occurred
			logger.d("Default folder created successfully")
		} catch (error: Exception) {
			// Log detailed error message but do not propagate the exception
			logger.e("Error creating default folder: ${error.message}", error)
		}
	}
	
	companion object {
		
		/**
		 * Logger instance for the [AIOSettings] class.
		 *
		 * Utilized for logging events, errors, and debug information related to
		 * settings management, including serialization, deserialization, and validation processes.
		 * This helps in monitoring the application's state and diagnosing issues.
		 */
		private val logger = LogHelperUtils.from(AIOSettings::class.java)
		
		/**
		 * Filename used as a flag to indicate that dark mode is enabled.
		 *
		 * This file's existence within the app's internal storage signals that the dark theme
		 * should be active. It provides a simple, file-based mechanism for persisting the
		 * dark mode state, complementing the `themeAppearance` setting.
		 *
		 * @see themeAppearance for the primary theme configuration.
		 */
		const val AIO_SETTING_DARK_MODE_FILE_NAME: String = "darkmode.on"
		
		/**
		 * Filename for storing user or app settings in a human-readable JSON format.
		 *
		 * This file serves as a fallback for `AIO_SETTINGS_FILE_NAME_BINARY` and is also
		 * useful for debugging or manual inspection of settings. It is saved in the app's
		 * internal storage.
		 *
		 * @see AIO_SETTINGS_FILE_NAME_BINARY for the primary, performance-optimized binary format.
		 * @see updateInStorage
		 */
		const val AIO_SETTINGS_FILE_NAME_JSON: String = "aio_settings.json"
		
		/**
		 * Filename for storing user or app settings in a compact, high-performance binary format.
		 *
		 * This file is generated using FST (Fast-serialization) and serves as the primary,
		 * fast-loading source for application settings. It is read at startup before falling
		 * back to the JSON equivalent if this file is missing or corrupt.
		 *
		 * @see AIO_SETTINGS_FILE_NAME_JSON
		 * @see AIOSettings.saveToBinary
		 * @see AIOSettings.loadFromBinary
		 */
		const val AIO_SETTINGS_FILE_NAME_BINARY: String = "aio_settings.dat"
		
		/**
		 * Constant representing the app's private download folder.
		 * When this option is selected, downloaded files are stored in the application's
		 * internal directory, which is not accessible by other apps or the user directly
		 * through a file manager. This enhances privacy and prevents clutter in public folders.
		 *
		 * @see SYSTEM_GALLERY for the public storage option.
		 */
		const val PRIVATE_FOLDER = 1
		
		/**
		 * Constant representing the public download folder, typically the system's "Downloads"
		 * or a custom public directory.
		 *
		 * Files saved to this location are visible to other applications, such as file managers
		 * and media galleries (e.g., Photos, Gallery), and persist even if the app is uninstalled.
		 *
		 * @see PRIVATE_FOLDER for app-specific, private storage.
		 */
		const val SYSTEM_GALLERY = 2
		
		/**
		 * Deserializes a JSON string into an [AIOSettings] object.
		 *
		 * This function handles the conversion of a JSON representation of the application's
		 * settings back into a usable `AIOSettings` class instance. It is primarily used
		 * during startup to restore settings from a previously saved JSON file, ensuring
		 * backward compatibility and providing a fallback mechanism if binary deserialization fails.
		 *
		 * The process involves:
		 * 1. Encoding the input JSON string into a byte array.
		 * 2. Wrapping the byte array in a [ByteArrayInputStream] for the deserializer.
		 * 3. Using the application's shared `DslJson` instance (`aioDSLJsonInstance`) for
		 *    efficient and consistent parsing.
		 * 4. If deserialization fails or returns `null` (e.g., due to malformed JSON or
		 *    version mismatch), a new default `AIOSettings` instance is created to prevent
		 *    the application from starting in an invalid state.
		 *
		 * @param jsonString The JSON-formatted string to be converted.
		 * @return A fully populated [AIOSettings] object from the JSON string, or a
		 *         default instance if deserialization is unsuccessful.
		 */
		@JvmStatic
		fun convertJSONStringToClass(jsonString: String): AIOSettings {
			logger.d("Converting JSON to settings object")
			
			// Prepare a byte stream from the input JSON string
			val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
			
			// Deserialize the JSON into an AIOSettings object; fallback to default if null
			val loadedSettings: AIOSettings = aioDSLJsonInstance
				.deserialize(AIOSettings::class.java, inputStream) ?: AIOSettings()
			
			return loadedSettings
		}
	}
}