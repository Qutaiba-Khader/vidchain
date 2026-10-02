package app.core

import android.app.*
import androidx.documentfile.provider.*
import androidx.documentfile.provider.DocumentFile.*
import androidx.lifecycle.*
import app.core.AIOApp.Companion.aioHistory
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.internalDataFolder
import app.core.bases.*
import app.core.bases.language.*
import app.core.engines.backend.*
import app.core.engines.browser.bookmarks.*
import app.core.engines.browser.history.*
import app.core.engines.caches.*
import app.core.engines.downloader.*
import app.core.engines.downloader.DownloadModelsDBManager.getAllDownloadsWithRelationsAssembled
import app.core.engines.objectbox.*
import app.core.engines.objectbox.ObjectBoxManager.initializeObjectBoxDB
import app.core.engines.settings.*
import app.core.engines.supabase.*
import app.core.engines.supabase.SupabaseCloudServer.initializeSupabaseClient
import app.core.engines.user_profile.*
import app.core.engines.youtube.*
import com.aio.*
import com.anggrayudi.storage.file.DocumentFileCompat.fromPublicFolder
import com.anggrayudi.storage.file.PublicDirectory.*
import com.dslplatform.json.*
import com.google.gson.*
import com.google.gson.Strictness.*
import com.yausername.ffmpeg.*
import com.yausername.youtubedl_android.*
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import kotlinx.coroutines.*
import lib.networks.*
import lib.networks.URLUtilityKT.isInternetConnected
import lib.process.*
import lib.process.AsyncJobUtils.*
import lib.process.ThreadsUtility.executeInBackground

/**
 * Main Application class for the AIO (All-In-One) application.
 *
 * This class serves as the central hub for the entire application, responsible
 * for:
 * - Managing the global application context and lifecycle.
 * - Initializing and coordinating all core systems and engines.
 * - Providing centralized access to global singletons and managers.
 * - Implementing a priority-based startup sequence for optimal performance.
 * - Handling application-level configuration and feature flags.
 *
 * ## Architecture Overview:
 * - **Singleton Pattern**: Provides global access to application components via
 *   `AIOApp.INSTANCE`.
 * - **Layered Initialization**: A multi-stage, priority-based startup minimizes
 *   launch time by executing tasks concurrently. Critical path systems are
 *   initialized first, followed by high-priority and background tasks.
 * - **Dependency Centralization**: Acts as a single source of truth for all core
 *   engines (settings, database, download system, etc.), simplifying dependency
 *   management.
 * - **Lifecycle Awareness**: Integrates with Android's lifecycle to manage
 *   resources effectively and prevent memory leaks.
 * - **Error Resilience**: Implements graceful degradation with comprehensive
 *   fallback strategies (e.g., using legacy file storage if the database
 *   fails to initialize).
 *
 * ## Key Features:
 * - **Multi-stage Startup**: Orchestrated by the nested `StartupManager` class,
 *   which categorizes tasks into critical, high-priority, and background queues.
 * - **Centralized Access**: Provides `get()` methods for core modules like settings,
 *   bookmarks, history, and the download system.
 * - **Integrated ObjectBox Database**: Manages the ObjectBox database for local
 *   persistence of application data.
 * - **Media Processing**: Integrates YouTube-DL and FFmpeg for downloading and
 *   processing online media.
 * - **Comprehensive Logging**: Utilizes `LogHelperUtils` for detailed logging of
 *   the application lifecycle and startup sequence.
 * - **Memory Management**: Implements strategies like weak references for activities
 *   to prevent memory leaks.
 *
 * ## Startup Sequence:
 */
class AIOApp : LocaleApplicationImpl(), LifecycleObserver {
	
	/**
	 * Logger instance for comprehensive application lifecycle tracking.
	 * Provides detailed insights into startup sequencing, system initialization,
	 * and operational metrics throughout the application lifecycle.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	companion object {
		
		/**
		 * Global application instance for context access throughout the app.
		 * Uses volatile publication for thread-safe initialization.
		 */
		@Volatile
		lateinit var INSTANCE: AIOApp
		
		/**
		 * A global flag to enable or disable debug mode across the application.
		 *
		 * When `true`, this flag activates development-specific features, such as
		 * verbose logging, UI debugging tools, and experimental functionalities.
		 * It should be set to `false` in production builds to optimize performance
		 * and prevent the exposure of sensitive debug information.
		 *
		 * @see LogHelperUtils for its impact on logging verbosity.
		 */
		var IS_DEBUG_MODE_ON = BuildConfig.IS_DEBUG_MODE_ON
		
		/**
		 * A global flag to control the cloud backup and synchronization feature.
		 *
		 * When `true`, the application will attempt to back up user data, such as
		 * settings, bookmarks, and history, to a remote cloud service. It also
		 * enables data restoration capabilities. Setting this to `false` disables
		 * all cloud-related network activity and UI components.
		 *
		 * @see AIOBackend for the underlying implementation.
		 */
		var IS_CLOUD_BACKUP_ENABLED = false
		
		/**
		 * A global flag indicating whether the "Ultimate" version of the application
		 * is unlocked.
		 *
		 * When `true`, this flag grants access to the highest tier of premium features,
		 * which may include advanced customization, exclusive tools, and unlimited usage
		 * quotas. This is typically set after a specific in-app purchase or license
		 * verification.
		 */
		var IS_ULTIMATE_VERSION_UNLOCKED = true
		
		/**
		 * A global flag to identify whether the current user is a "Premium" subscriber.
		 *
		 * When `true`, this flag unlocks features and content available to paying users
		 * or subscribers. It is used throughout the app to conditionally enable or
		 * disable functionality based on the user's subscription status.
		 */
		var IS_PREMIUM_USER = true
		
		/**
		 * A lazy-initialized `DocumentFile` representing the application's internal,
		 * private data directory.
		 *
		 * This directory is located within the app's sandboxed storage and is not
		 * accessible to other applications or the user directly. It is the ideal
		 * location for storing sensitive data, databases, and caches.
		 *
		 * - **Path:** `/data/data/<package_name>/files/`
		 * - **Accessibility:** Private to the application.
		 * - **Persistence:** Data is removed when the app is uninstalled.
		 */
		val internalDataFolder: DocumentFile by lazy { fromFile(INSTANCE.filesDir) }
		
		/**
		 * A lazy-initialized `DocumentFile` representing the application's external,
		 * app-specific data directory.
		 *
		 * This directory is located on external storage (which may be emulated) and is
		 * intended for app-specific files that do not need to be private. While scoped
		 * to the application, it may be accessible by other apps with appropriate
		 * permissions. The directory and its contents are removed when the app is uninstalled.
		 *
		 * - **Path:** `/storage/emulated/0/Android/data/<package_name>/files/`
		 * - **Accessibility:** App-specific but potentially accessible by other apps.
		 * - **Returns:** A `DocumentFile` or `null` if external storage is unavailable.
		 */
		val externalDataFolder: DocumentFile? by lazy {
			INSTANCE.getExternalFilesDir(null)?.let { fromFile(it) }
		}
		
		/**
		 * A global [CoroutineScope] tied to the application's lifecycle, designed for
		 * managing long-running background tasks.
		 *
		 * This scope is automatically cancelled when the application process is terminated,
		 * ensuring that all associated coroutines are cleaned up properly to prevent
		 * memory leaks and orphaned jobs. It is ideal for operations that need to
		 * persist for the duration of the application's lifetime but should not block
		 * the main thread.
		 *
		 * It uses a `SupervisorJob` to ensure that the failure of one child coroutine
		 * does not cancel the entire scope, making it resilient to isolated errors.
		 *
		 * ### Example Usage:
		 * ```kotlin
		 * AIOApp.applicationJob.launch {
		 *     // Perform a long-running background task, e.g., data sync
		 * }
		 * ```
		 *
		 * @see CoroutineScope
		 * @see SupervisorJob
		 * @see Dispatchers.IO
		 */
		private val applicationJob = SupervisorJob()
		
		/**
		 * A global [CoroutineScope] tied to the application's lifecycle.
		 *
		 * This scope is the primary choice for launching long-running coroutines that
		 * need to persist for the entire duration of the application's life, independent
		 * of any specific `Activity` or `ViewModel`. It is automatically cancelled
		 * when the application process is terminated, ensuring that all coroutines
		 * launched within it are properly cleaned up to prevent leaks.
		 *
		 * It is configured with `Dispatchers.Default` and a `SupervisorJob`, which
		 * provides resilience: if one child coroutine fails, it will not cancel
		 * the scope or its other children.
		 *
		 * ### Use Cases:
		 * - Initiating background data synchronization.
		 * - Running long-term monitoring services.
		 * - Performing one-off asynchronous tasks that are not tied to a UI component.
		 *
		 * @see CoroutineScope
		 * @see SupervisorJob
		 * @see Dispatchers.Default
		 */
		private val applicationScope = CoroutineScope(Dispatchers.Main + applicationJob)
		
		/**
		 * Global application settings and user preferences manager.
		 *
		 * This singleton instance, initialized during the critical startup path, provides
		 * centralized access to all user-configurable settings. It handles the persistence
		 * and retrieval of preferences from the local database, ensuring that user choices
		 * are maintained across application sessions.
		 *
		 * @see AIOSettings for the data model.
		 * @see AIOSettingsDBManager for the persistence logic.
		 * @see getAIOSettings for the public accessor.
		 */
		lateinit var aioSettings: AIOSettings
		
		/**
		 * Manages the currently logged-in user's profile information.
		 *
		 * This lazily-initialized singleton holds the data model for the user's profile,
		 * which may include details such as username, email, subscription status, and
		 * cloud sync preferences. It is initialized on first access and serves as the
		 * single source of truth for user-specific data throughout the application.
		 *
		 * @see AIOUser for the underlying data model.
		 */
		lateinit var aioUserProfile: AIOUserProfile
		
		/**
		 * Browser bookmarks management system.
		 *
		 * Initialized during application startup, this singleton manages all user bookmarks.
		 * It provides CRUD (Create, Read, Update, Delete) operations and handles the
		 * persistence of bookmark data in the local ObjectBox database.
		 *
		 * @see AIOBookmarks for the data model and management logic.
		 * @see AIOBookmarksDBManager for persistence.
		 * @see getAIOBookmarks for the public accessor.
		 */
		lateinit var aioBookmark: AIOBookmarks
		
		/**
		 * Browsing history tracking and management system.
		 *
		 * This singleton maintains a chronological record of the user's browsing sessions.
		 * It is initialized during startup by loading data from the local ObjectBox database,
		 * and it provides methods for adding new history entries and querying past records.
		 *
		 * @see AIOHistory for the data model and management logic.
		 * @see AIOHistoryDBManager for persistence.
		 * @see getAIOHistory for the public accessor.
		 */
		lateinit var aioHistory: AIOHistory
		
		/**
		 * Raw file resources and asset management system.
		 *
		 * This singleton handles the loading and caching of application resources, such as
		 * Lottie animations, from the raw assets folder. It is initialized during the
		 * critical startup path to preload essential resources, ensuring they are
		 * immediately available and preventing UI lag.
		 *
		 * @see AIORawFiles for implementation details.
		 */
		lateinit var aioRawFiles: AIORawFiles
		
		/**
		 * YouTube-DL instance for video extraction and processing.
		 *
		 * This singleton provides the core functionality for downloading and processing
		 * online video content using the `yt-dlp` library. It is initialized in the
		 * background during application startup to avoid blocking the UI. The instance
		 * handles library updates and configuration.
		 *
		 * @see YoutubeDL for the underlying library wrapper.
		 * @see initializeYtDLP for the initialization logic.
		 */
		lateinit var ytdlpInstance: YoutubeDL
		
		/**
		 * Website favicon (favorite icon) management and caching system.
		 *
		 * This lazily-initialized singleton handles the entire lifecycle of website favicons,
		 * including fetching them from URLs, caching them locally for performance, and
		 * providing them to the UI on demand. It helps reduce network usage and improves
		 * UI responsiveness by avoiding repeated downloads of the same icon.
		 *
		 * @see AIOFavicons for implementation details.
		 */
		val aioFavicons: AIOFavicons by lazy { AIOFavicons() }
		
		/**
		 * Advertisement blocking and content filtering engine.
		 *
		 * This lazily-initialized singleton provides real-time ad blocking capabilities
		 * during browsing sessions. It uses filter lists to identify and block requests
		 * for advertisements and trackers, enhancing user privacy and improving page
		 * load times.
		 *
		 * @see AIOAdBlocker for configuration and usage.
		 */
		val aioAdblocker: AIOAdBlocker by lazy { AIOAdBlocker() }
		
		/**
		 * Backend service integration and API management system.
		 *
		 * This lazily-initialized singleton manages all network communications with backend
		 * services. It abstracts away API endpoints and data synchronization logic,
		 * providing a clean interface for features like cloud backup and remote configuration.
		 *
		 * @see AIOBackend for API integration details.
		 */
		val aioBackend: AIOBackend by lazy { AIOBackend() }
		
		/**
		 * Internationalization and localization management system.
		 *
		 * A lazily-initialized singleton that provides multi-language support and manages
		 * text resources. It allows the application to dynamically switch languages and
		 * ensures that all UI components display appropriately translated strings.
		 *
		 * @see AIOLanguage for language management and string retrieval.
		 */
		val aioLanguage: AIOLanguage by lazy { AIOLanguage() }
		
		/**
		 * Google GSON instance configured for lenient JSON parsing.
		 *
		 * A lazily-initialized, shared Gson instance for JSON serialization and deserialization.
		 * It is configured with `LENIENT` strictness to handle malformed or non-standard JSON
		 * from various web sources, preventing parsing errors and improving robustness.
		 *
		 * @see Gson
		 * @see Strictness.LENIENT
		 */
		val aioGSONInstance: Gson by lazy { GsonBuilder().setStrictness(LENIENT).create() }
		
		/**
		 * High-performance DSL-JSON instance for efficient JSON processing.
		 *
		 * A shared instance of DslJson, a high-performance JSON library optimized for speed
		 * and low memory allocation. It is used in performance-critical sections of the
		-		 * app where efficient JSON processing is paramount.
		 *
		 * @see DslJson
		 */
		val aioDSLJsonInstance = DslJson<Any>()
		
		/**
		 * Comprehensive download management and execution system.
		 *
		 * The lazily-initialized singleton responsible for all file download operations.
		 * It manages the entire download lifecycle, including queuing, progress tracking,
		 * pausing, resuming, and error handling.
		 *
		 * @see DownloadSystem
		 */
		val downloadSystem: DownloadSystem by lazy { DownloadSystem() }
		
		/**
		 * Download model consolidation and data merging utility.
		 * Provides capabilities for combining and organizing download metadata, particularly
		 * for merging partial download information into a complete model.
		 */
		val downloadModelBinaryMerger: DownloadModelBinaryMerger by lazy { DownloadModelBinaryMerger() }
		
		/**
		 * Application-level timer for scheduling periodic tasks.
		 *
		 * This lazily-initialized timer is configured to run a task periodically. In this
		 * configuration, it fires every hour (`3600000` ms) with an initial delay of `200` ms.
		 * It is used for background jobs like checking for updates or performing routine cleanup.
		 * The timer is automatically started upon its first access.
		 *
		 * @see AIOTimer
		 */
		val aioTimer: AIOTimer by lazy { AIOTimer(3600000, 200).apply { start() } }
		
		/**
		 * User engagement and application usage tracking system.
		 * This lazily-initialized system monitors and records user interaction patterns,
		 * such as session duration and feature usage, for analytics and optimization purposes.
		 *
		 * @see AppUsageTimer
		 */
		val aioUsageTimer: AppUsageTimer by lazy { AppUsageTimer() }
	}
	
	/**
	 * Startup manager responsible for orchestrating the priority-based initialization sequence.
	 * Ensures optimal application startup performance through categorized task execution.
	 */
	private val startupManager = StartupManager()
	
	/**
	 * Application entry point. Initializes all core systems using a multi-stage startup sequence.
	 *
	 * This method orchestrates a sophisticated, priority-based initialization process
	 * designed to optimize startup time and ensure application stability. It categorizes
	 * tasks into critical, high-priority, and background jobs, executing them in a
	 * manner that prioritizes user-facing functionality.
	 *
	 * ### Startup Sequence:
	 * 1.  **Foundation Setup**: Registers the global application instance and initializes the
	 *     ObjectBox database. This is a mandatory first step.
	 * 2.  **Critical Path (Blocking)**: Sequentially initializes essential systems required
	 *     for the app to function. This includes the HTTP client, settings, download system,
	 *     and core resource preloading. The UI thread is blocked until this phase completes.
	 * 3.  **High-Priority (Concurrent)**: Concurrently loads user-facing data and components
	 *     that are important but not immediately required for the app to start, such as
	 *     bookmarks, browsing history, and activity lifecycle management.
	 * 4.  **Background (Concurrent)**: Kicks off non-essential, long-running, or I/O-bound
	 *     tasks like initializing the YouTube-DL engine and starting analytics tracking.
	 *     These run with the lowest priority to avoid impacting performance.
	 *
	 * ### Error Handling and Resilience:
	 * - Each initialization step is wrapped in a `try-catch` block to prevent a single
	 *   failure from crashing the entire application.
	 * - Implements graceful fallbacks, such as reverting to legacy file-based storage if
	 *   a database operation fails.
	 * - Logs detailed success and failure messages for easier debugging.
	 * - Allows the application to continue with degraded functionality if non-critical
	 *   systems (e.g., YouTube parser, animations) fail to initialize.
	 *
	 */
	override fun onCreate() {
		super.onCreate()
		logger.d("Application startup sequence initiated")
		
		INSTANCE = this
		initializeObjectBoxDB(INSTANCE)
		initializeSupabaseClient()
		aioBackend.initParseBackend()
		
		startupManager.apply {
			initializeCriticalServices()
			initializePriorityServices()
			initializeBackgroundServices()
		}.let { performStartupExecution(it) }
		
		logger.d("Application startup sequence completed")
		
	}
	
	/**
	 * Orchestrates and executes the entire multi-stage startup sequence.
	 *
	 * This function is the central point for application initialization. It ensures that
	 * tasks are executed in the correct order of priority to optimize startup time
	 * and user experience.
	 *
	 * ### Execution Flow:
	 * 1.  **Executes Critical Tasks:** Calls `startupManager.executeCriticalTasks()`, which
	 *     runs all essential, blocking initializations on the main thread. This ensures
	 *     the application's core systems (e.g., settings, database connections) are
	 *     ready before any other operations begin.
	 *
	 * 2.  **Launches Concurrent Tasks:** Immediately after the critical path is complete,
	 *     it calls `startupManager.executeHighPriorityTasks()` and
	 *     `startupManager.executeBackgroundTasks()`. These methods launch non-blocking
	 *     coroutines to handle user-facing data loading and long-running background
	 *     initializations, respectively. This allows the UI to become responsive while
	 *     less critical systems initialize in the background.
	 *
	 * This strategic division of labor—blocking for the essentials and concurrent for the
	 * rest—is key to achieving a fast and efficient application launch.
	 *
	 * @see StartupManager.executeCriticalTasks
	 * @see StartupManager.executeHighPriorityTasks
	 * @see StartupManager.executeBackgroundTasks
	 */
	private fun performStartupExecution(startupManager: StartupManager) {
		logger.d("Executing critical path tasks...")
		startupManager.executeCriticalTasks()
		
		logger.d("Executing concurrent tasks...")
		startupManager.executeHighPriorityTasks()
		startupManager.executeBackgroundTasks()
		logger.d("All startup tasks initiated")
	}
	
	/**
	 * Initializes and orchestrates the execution of critical, high-priority, and background services
	 * during the application startup sequence. This function is the central point for configuring
	 * the multi-stage startup process, ensuring that essential services are loaded first,
	 * followed by non-blocking concurrent tasks.
	 *
	 * This method uses a `StartupManager` to categorize and execute tasks:
	 * 1.  **Critical Tasks**: These are foundational services essential for the application to function.
	 *     They are executed synchronously on the main thread and block until completion. This
	 *     includes initializing settings, the HTTP client, the download system, and core resources.
	 *
	 * 2.  **High-Priority Tasks**: Important for user-facing features but can be loaded concurrently.
	 *     These tasks, such as loading bookmarks and browsing history, run on a background
	 *     thread pool to avoid blocking the UI.
	 *
	 * 3.  **Background Tasks**: Long-running or non-essential services that can be initialized
	 *     asynchronously without impacting startup performance. This includes initializing
	 *     YouTube-DL and starting usage tracking.
	 *
	 * The method defines all tasks within their respective priority groups and then triggers
	 * their execution via the `StartupManager`. This ensures an optimized, resilient, and
	 * fast application launch.
	 *
	 * @see StartupManager for the implementation of the priority-based execution logic.
	 * @see AIOApp.onCreate where this method is called to initiate the startup process.
	 */
	private fun StartupManager.initializeCriticalServices(
		onSettingsLoaded: () -> Unit = {},
		onUserProfileLoaded: () -> Unit = {}
	) {
		addCriticalTask { // Critical tasks block the main thread.
			warmUpOkHttpClient()
			logger.d("[Startup] Critical: Loading system configurations...")
			loadSystemConfigurations(onSettingsLoaded)
			logger.d("[Startup] Critical: System configurations loaded.")
			
			logger.d("[Startup] Critical: Loading application user profile...")
			loadApplicationUserProfile(onUserProfileLoaded)
			logger.d("[Startup] Critical: application user profile loaded.")
			
			logger.d("[Startup] Critical: Initializing download system...")
			initializeDownloadSystemWithModels()
			logger.d("[Startup] Critical: Download system initialized.")
			
			logger.d("[Startup] Critical: Preloading raw files...")
			preloadApplicationRawFiles()
			logger.d("[Startup] Critical: Raw files preloaded.")
			
			logger.d("[Startup] Critical: Initializing YouTube services...")
			initializeYouTubeServices()
			logger.d("[Startup] Critical: YouTube services initialized.")
		}
	}
	
	/**
	 * Initializes the YouTube-DL (yt-dlp) and FFmpeg libraries.
	 *
	 * This function handles the setup for video downloading and media processing capabilities.
	 * It performs the following steps in a background thread to avoid blocking the UI:
	 * 1.  Initializes the `YoutubeDL` singleton instance.
	 * 2.  Initializes the `FFmpeg` singleton instance.
	 * 3.  Checks for an active internet connection. If available, it triggers an
	 *     asynchronous update of the `yt-dlp` binaries to ensure the latest version
	 *     is being used.
	 * 4.  Assigns the initialized `YoutubeDL` instance to the global `ytdlpInstance`
	 *     property for app-wide access.
	 *
	 * Any exceptions during initialization are caught and logged, preventing the app from
	 * crashing if the libraries fail to initialize.
	 *
	 * @see YoutubeDL.init
	 * @see FFmpeg.init
	 * @see YoutubeDL.updateYoutubeDL
	 */
	private fun initializeYouTubeServices() {
		try {
			logger.d("[Startup] Initializing YouTube services...")
			NewPipeExtractorLib.initSystem()
			YouTubeVideoAPIService.fetchingTopTrendingVideos()
			logger.d("[Startup] YouTube services initialized successfully")
		} catch (error: Exception) {
			logger.e("[Startup] Failed to initialize YouTube services: ${error.message}", error)
		}
	}
	
	/**
	 * Preloads and caches essential raw file resources, such as Lottie animations.
	 *
	 * This function initializes the [AIORawFiles] manager, which is responsible for
	 * loading raw resources from the application's assets and caching them for
	 * fast access. By preloading these files during application startup, it
	 * prevents UI stutters or delays when the resources are first needed.
	 *
	 * This operation is executed as part of the critical startup path to ensure
	 * that essential UI animations and resources are immediately available.
	 * If preloading fails, an error is logged, but the application continues
	 * to launch, albeit with potentially missing resources.
	 *
	 * @see AIORawFiles.preloadLottieAnimation for the specific preloading implementation.
	 */
	private fun preloadApplicationRawFiles() {
		try {
			logger.d("[Startup] Preloading lottie animations...")
			aioRawFiles = AIORawFiles().apply(AIORawFiles::preloadLottieAnimation)
			logger.d("[Startup] Preloaded lottie animations successfully")
		} catch (error: Exception) {
			logger.e("[Startup] Failed to preload lottie animations: ${error.message}", error)
		}
	}
	
	/**
	 * Initializes or re-initializes the download system with a provided list of download models.
	 *
	 * This function is designed to be called when a fresh or updated set of download models
	 * is available, for instance, after a data synchronization or a full database reload.
	 * It performs the following steps in a background thread to avoid blocking the UI:
	 * 1.  Clears any existing models from the `downloadSystem`'s prefetch cache.
	 * 2.  Adds the new list of `newModels` to the prefetch cache.
	 * 3.  Calls `downloadSystem.initializeSystem()` to re-process the models and update the
	 *     system's internal state, ensuring the UI and download logic reflect the new data.
	 *
	 * @param newModels A list of [DownloadModel] objects to populate the download system with.
	 *                  This will replace any previously loaded models.
	 * @see DownloadSystem.initializeSystem
	 * @see DownloadModelsDBManager.getAllDownloadsWithRelationsAssembled
	 */
	private fun initializeDownloadSystemWithModels() {
		try {
			getAllDownloadsWithRelationsAssembled().let {
				logger.d("[Startup] Preloading ${it.size} download models from database...")
				downloadSystem.prefetchedEntireDownloadModels.addAll(it)
				downloadSystem.initializeSystem()
			}
		} catch (error: Exception) {
			logger.e("[Startup] Failed to initialize download system: ${error.message}", error)
			logger.d("[Startup] Continuing with empty download system")
		}
	}
	
	/**
	 * Pre-initializes and caches the shared OkHttp client instance.
	 *
	 * This method proactively creates and configures the application's global
	 * HTTP client. By "warming it up" during the critical startup phase,
	 * subsequent network requests can be executed more quickly, as the overhead
	 * of client initialization (e.g., setting up connection pools, certificate
	 * chains, and thread dispatchers) is eliminated.
	 *
	 * This is a performance optimization to reduce the latency of the first
	 * network-dependent operation in the app.
	 *
	 * @see HttpClientProvider.initialize
	 */
	private fun warmUpOkHttpClient() {
		logger.d("[Startup] Critical: Warming up OkHttp client...")
		HttpClientProvider.initialize()
		logger.d("[Startup] Critical: OkHttp client warmed up.")
	}
	
	/**
	 * Loads and initializes the core system configurations, primarily the application settings.
	 *
	 * This function handles the loading of `aioSettings`. It implements a robust,
	 * fault-tolerant strategy by first attempting to load the settings from the primary
	 * database source (ObjectBox). If any error occurs during this process, it gracefully
	 * falls back to a legacy file-based storage system (`readObjectFromStorage`) to
	 * ensure the application remains functional even with database issues.
	 *
	 * This method is a critical part of the application's startup sequence, ensuring that all
	 * user-specific configurations are available before the UI is presented.
	 *
	 * ### Loading Strategy:
	 * 1.  **Attempt Database Load**: Tries to initialize `aioSettings` from the
	 *     `AIOSettingsDBManager`.
	 * 2.  **Error Handling & Fallback**: If a database-related `Exception` is caught,
	 *     it logs the error and loads the settings from a legacy file.
	 * 3.  **Resilience**: This approach ensures that a database failure does not prevent
	 *     the application from starting, promoting overall resilience.
	 *
	 * @see AIOSettingsDBManager.loadSettingsFromDB
	 * @see BaseObservable.readObjectFromStorage for the legacy fallback mechanism.
	 */
	private fun loadSystemConfigurations(onSettingsLoaded: () -> Unit) {
		try {
			logger.d("[Startup] Loading settings from database...")
			aioSettings = AIOSettingsDBManager.loadSettingsFromDB()
			aioSettings.updateInStorage()
		} catch (error: Exception) {
			logger.e(
				"[Startup] Failed to load settings from database, " +
					"falling back to legacy storage.", error
			)
			aioSettings = AIOSettings().apply(AIOSettings::readObjectFromStorage)
			aioSettings.updateInStorage()
			onSettingsLoaded.invoke()
		}
	}
	
	/**
	 * Loads the application user profile from the database.
	 *
	 * This function is responsible for retrieving the current user's profile information,
	 * such as username, preferences, and subscription status, from the local ObjectBox
	 * database. It initializes the `aioUserProfile` singleton instance, making the
	 * user's data accessible throughout the application.
	 *
	 * This operation is executed as part of the high-priority startup sequence, ensuring
	 * that user-specific data is available early in the application lifecycle without
	 * blocking the main thread.
	 *
	 * If the database fails to load the profile, an error is logged, and the
	 * application proceeds with a default or empty user profile, ensuring resilience.
	 *
	 * @see AIOUserProfileDBManager.loadUserProfileFromDB for the database retrieval logic.
	 * @see AIOUserProfile for the data model.
	 */
	private fun loadApplicationUserProfile(onUserProfileLoaded: () -> Unit = {}) {
		try {
			logger.d("[Startup] Loading user profile from database...")
			aioUserProfile = AIOUserProfileDBManager.loadSettingsFromDB()
			aioUserProfile.updateInStorage()
			SupabaseCloudServer.observeSupabaseAuthState(applicationScope)
		} catch (error: Exception) {
			logger.e(
				"[Startup] Failed to load user profile from database, " +
					"falling back to legacy storage.", error
			)
			aioUserProfile = AIOUserProfile().apply(AIOUserProfile::readObjectFromStorage)
			aioUserProfile.updateInStorage()
			onUserProfileLoaded.invoke()
		}
	}
	
	/**
	 * Initializes and executes a series of priority-based startup services.
	 * This method is intended to be called during application startup to perform
	 * essential initializations in a structured and efficient manner. It handles
	 * both critical, blocking tasks and non-critical, asynchronous tasks.
	 *
	 * @param criticalTasks A list of essential, high-priority tasks that must be
	 *   completed sequentially before the application can proceed. These tasks will
	 *   block the calling thread. Examples include loading settings or initializing
	 *   core databases.
	 * @param backgroundTasks A list of non-essential, lower-priority tasks that
	 *   can run concurrently in the background without blocking the main startup
	 *   sequence. These are typically IO-bound or long-running operations like
	 *   updating libraries or starting secondary services.
	 */
	private fun StartupManager.initializePriorityServices() {
		addHighPriorityTask {
			logger.d("[Startup] Phase 2: Loading user data and UI components...")
			loadAIOBookmarksFromDB()
			loadsAIOWebHistoryFromDB()
			startActivityLifecycleManagement()
			logger.d("[Startup] User data and UI components loading completed")
		}
	}
	
	/**
	 * Sets up the application-wide activity lifecycle management system.
	 *
	 * This function registers a global `ActivityLifecycleCallbacks` listener that monitors
	 * the state of all activities within the application. Its primary responsibilities are:
	 * 1.  **Detecting Activity Destruction**: It listens for the `onActivityDestroyed` event.
	 * 2.  **Preventing Memory Leaks**: When an activity of type `BaseActivity` is destroyed,
	 *     it invokes `clearWeakActivityReference()` on that activity instance. This is a crucial
	 *     step to release any static or long-lived references to the activity context, thereby
	 *     preventing common memory leaks associated with Android's activity lifecycle.
	 *
	 * The registration is performed on the main thread to ensure thread safety and proper
	 * interaction with the Android framework.
	 *
	 * @see AIOLifeCycle The custom, simplified lifecycle callback interface used.
	 * @see BaseActivity.clearWeakActivityReference for the implementation of the cleanup logic.
	 * @see Application.registerActivityLifecycleCallbacks for the underlying Android API.
	 */
	private fun startActivityLifecycleManagement() {
		try {
			logger.d("[Startup] Initializing activity lifecycle management...")
			manageActivityLifeCycle()
			logger.d("[Startup] Activity lifecycle management initialized successfully")
		} catch (error: Exception) {
			logger.e("[Startup] Failed to initialize activity lifecycle management: ${error.message}", error)
		}
	}
	
	/**
	 * Loads the web browsing history from the ObjectBox database.
	 *
	 * This function retrieves all stored `WebHistory` entities, maps them to `AIOHistory`
	 * objects, and aggregates them into a single `AIOHistory` instance. It ensures that
	 * the most recent browsing data is available upon application startup.
	 *
	 * If the database is empty or an error occurs during retrieval, an empty `AIOHistory`
	 * object is returned, preventing crashes and allowing the application to continue.
	 *
	 * @return An `AIOHistory` object containing all browsing history records from the database,
	 *         or an empty `AIOHistory` object if no records are found or an error occurs.
	 * @see AIOHistory
	 * @see WebHistory
	 * @see ObjectBoxManager
	 */
	private fun loadsAIOWebHistoryFromDB() {
		try {
			logger.d("[Startup] Loading browsing history from storage...")
			aioHistory = AIOHistoryDBManager.loadAIOHistoryFromDB()
			aioHistory.updateInStorage()
			logger.d("[Startup] Browsing history loaded successfully")
		} catch (error: Exception) {
			logger.e("[Startup] Failed to load browsing history: ${error.message}", error)
			aioHistory = AIOHistory().apply(AIOHistory::readObjectFromStorage)
			aioHistory.updateInStorage()
			logger.d("[Startup] Using legacy history storage as fallback")
		}
	}
	
	/**
	 * Loads bookmarks from the ObjectBox database.
	 *
	 * This method retrieves all `AIOBookmarksCollectionDB` entities from the database,
	 * converts them into a list of `AIOBookmarksCollection` objects, and then
	 * populates a new `AIOBookmarks` instance with this data. The collections
	 * are sorted by their `index` property in ascending order to maintain user-defined ordering.
	 *
	 * If the database is empty or no bookmarks are found, it returns an `AIOBookmarks`
	 * instance with an empty list of collections. This ensures that the application
	 * always receives a valid, non-null object, preventing potential null pointer exceptions.
	 *
	 * @return An `AIOBookmarks` object containing all bookmarks loaded from the database,
	 *         or an empty `AIOBookmarks` object if none are found.
	 * @see AIOBookmarksCollectionDB for the database entity.
	 * @see AIOBookmarksCollection for the in-memory data model.
	 * @see ObjectBoxManager.aioBookmarksCollectionDB for the database accessor.
	 */
	private fun loadAIOBookmarksFromDB() {
		try {
			logger.d("[Startup] Loading bookmarks from database...")
			aioBookmark = AIOBookmarks()
			aioBookmark = AIOBookmarksDBManager.loadAIOBookmarksFromDB()
			aioBookmark.updateInStorage()
			logger.d("[Startup] Bookmarks loaded successfully")
			
		} catch (error: Exception) {
			logger.e("[Startup] Failed to load bookmarks from database: ${error.message}", error)
			aioBookmark = AIOBookmarks().apply(AIOBookmarks::readObjectFromStorage)
			aioBookmark.updateInStorage()
			logger.d("[Startup] Using legacy bookmarks storage as fallback")
		}
	}
	
	/**
	 * A composite function that orchestrates the initialization of various non-essential,
	 * long-running background services. This function is typically called as a single
	 * background task during the application's startup sequence.
	 *
	 * It ensures that services which are not critical for the initial user interaction
	 * are loaded asynchronously on a background thread, preventing any impact on the
	 * application's startup time and UI responsiveness.
	 *
	 * The services initialized here include:
	 * 1.  **YouTube-DL & FFmpeg**: Initializes the core libraries required for video
	 *     and audio processing. Includes a network-aware check to update binaries
	 *     if an internet connection is available.
	 * 2.  **UI Session Tracking**: Starts the system responsible for monitoring user
	 *     engagement and application usage analytics.
	 *
	 * This method is designed to be self-contained and is executed within a coroutine
	 * on an I/O-optimized dispatcher.
	 *
	 * @see initializeYtDLP
	 * @see startAppUISessionTracking
	 * @see StartupManager.addBackgroundTask
	 */
	private fun StartupManager.initializeBackgroundServices() {
		addBackgroundTask {
			logger.d("[Startup] Phase 3: Initializing background services...")
			initializeYtDLP()
			startAppUISessionTracking()
			logger.d("[Startup] Background services initialization completed")
		}
	}
	
	/**
	 * Manages the application's activity lifecycle to prevent memory leaks and ensure
	 * robust state management.
	 *
	 * This function registers a global `ActivityLifecycleCallbacks` listener that automatically
	 * responds to activity lifecycle events. Its primary responsibility is to clear references
	 * to destroyed activities, which is a critical practice for preventing memory leaks,
	 * especially in complex applications with long-lived background processes.
	 *
	 * ### Key Responsibilities:
	 * - **Memory Leak Prevention**: By listening for `onActivityDestroyed`, it ensures that
	 *   references held by or to the activity are nullified, allowing the garbage collector
	 *   to reclaim memory.
	 * - **Centralized Lifecycle Logic**: Provides a single, consistent point for handling
	 *   activity destruction cleanup across the entire application.
	 * - **Integration with BaseActivity**: Works in tandem with `BaseActivity` by invoking
	 *   its `clearWeakActivityReference()` method upon destruction.
	 *
	 * The callback is executed on the main thread to ensure thread safety when interacting
	 * with UI components and activity contexts.
	 *
	 * @see AIOLifeCycle The custom interface for observing activity lifecycle events.
	 * @see BaseActivity.clearWeakActivityReference The specific cleanup method called on
	 *     activity destruction.
	 * @see Application.registerActivityLifecycleCallbacks The underlying Android framework API used.
	 */
	private fun manageActivityLifeCycle() {
		logger.d("Registering global activity lifecycle callbacks")
		executeOnMainThread {
			registerActivityLifecycleCallbacks(object : AIOLifeCycle {
				/**
				 * Called when any activity is destroyed, ensuring proper cleanup of references.
				 *
				 * @param activity The activity instance that is being destroyed
				 */
				override fun onActivityDestroyed(activity: Activity) {
					logger.d("Activity lifecycle: ${activity.javaClass.simpleName} destroyed")
					if (activity is BaseActivity) {
						activity.clearWeakActivityReference()
					}
				}
			})
		}
	}
	
	/**
	 * Initializes and starts the application usage tracking system on the main thread.
	 *
	 * This method delegates to {@link AppUsageTimer} to begin monitoring user
	 * engagement, session duration, and feature interaction. The data collected
	 * is used for analytics and to drive user experience improvements. It is
	 * executed on the main thread to ensure proper UI context.
	 *
	 * @see AppUsageTimer.startTracking for the underlying tracking implementation.
	 * @see StartupManager for how this task is orchestrated during app launch.
	 */
	private fun startAppUISessionTracking() {
		logger.d("Starting application usage tracking system")
		executeOnMainThread { aioUsageTimer.startTracking() }
	}
	
	/**
	 * Initializes the `yt-dlp` and FFmpeg libraries for media processing.
	 *
	 * This asynchronous method performs the following operations in the background to avoid
	 * blocking the main thread:
	 * 1.  Initializes the `yt-dlp` library using the application context.
	 * 2.  Initializes the FFmpeg library, which is required for media format conversion
	 *     and merging operations.
	 * 3.  Checks for an active internet connection and, if available, attempts to
	 *     update the `yt-dlp` binaries to the latest version. This ensures
	 *     compatibility with the latest video platform changes.
	 * 4.  Assigns the initialized `YoutubeDL` instance to a global variable for
	 *     app-wide access.
	 *
	 * Any exceptions during initialization are caught and logged to prevent app crashes,
	 * allowing the application to continue functioning, albeit with potentially
	 * degraded media download capabilities.
	 *
	 * @see YoutubeDL.init for the core library initialization details.
	 * @see FFmpeg.init for media processing setup.
	 * @see YoutubeDL.updateYoutubeDL for the binary update mechanism.
	 * @see executeInBackground for the asynchronous execution wrapper.
	 */
	fun initializeYtDLP() {
		executeInBackground(timeOutInMilli = 12000, codeBlock = {
			try {
				logger.d("Initializing YouTube-DL and FFmpeg libraries...")
				getInstance().init(this)
				FFmpeg.getInstance().init(this)
				logger.d("YouTube-DL and FFmpeg libraries initialized successfully")
				
				// Conditional binary updates based on network availability
				if (isInternetConnected()) {
					logger.d("Updating YouTube-DL binaries...")
					getInstance().updateYoutubeDL(INSTANCE)
				} // No else needed, skipping is the default action
			} catch (error: Exception) {
				logger.e("Error initializing YouTube-DL/FFmpeg: ${error.message}", error)
			}
			
			// Store the instance for global access
			ytdlpInstance = getInstance()
		})
	}
	
	/**
	 * Handles application termination by performing a graceful shutdown and resource cleanup.
	 *
	 * This method is called when the application is being terminated. It orchestrates a
	 * sequence of cleanup operations to ensure data integrity and release system resources.
	 * All operations are executed in a background thread with a timeout to prevent blocking
	 * the main thread and to guarantee a swift exit.
	 *
	 * ### Shutdown Sequence:
	 * 1.  **Pause Downloads**: All active and pending downloads are paused to prevent
	 *     incomplete files.
	 * 2.  **Release Download System**: The download engine's resources are cleaned up.
	 * 3.  **Stop Timers**: Application-wide timers and scheduled tasks are cancelled to halt
	 *     background activities.
	 * 4.  **Close Database**: The ObjectBox database connection is closed to commit any pending
	 *     transactions and release file handles.
	 *
	 * @see DownloadSystem.pauseAllDownloads
	 * @see DownloadSystem.cleanUp
	 * @see AIOTimer.stop
	 * @see ObjectBoxManager.closeObjectBoxDB
	 */
	override fun onTerminate() {
		logger.d("onTerminate: Application shutdown started")
		
		executeInBackground(timeOutInMilli = 1500, codeBlock = {
			logger.d("Shutdown: Pausing downloads")
			downloadSystem.pauseAllDownloads()
			downloadSystem.cleanUp()
			
			logger.d("Shutdown: Stopping timers")
			aioTimer.stop()
			
			logger.d("Shutdown: Closing database")
			ObjectBoxManager.closeObjectBoxDB()
			
			logger.d("Shutdown: Cleanup completed")
		})
		
		applicationScope.cancel()
		applicationJob.cancel()
		super.onTerminate()
		logger.d("onTerminate: Application shutdown finished")
	}
	
	/**
	 * Retrieves the application's internal data directory, a private storage location
	 * exclusively accessible by the app.
	 *
	 * This directory is ideal for storing sensitive application data that should not be
	 * accessible to other apps or the user directly. The typical path is
	 * `/data/data/<package_name>/files/`.
	 *
	 * @return A [DocumentFile] representing the internal private storage directory.
	 * @see internalDataFolder for the lazy-initialized backing property.
	 */
	fun getInternalDataFolder(): DocumentFile {
		logger.d("Accessing internal data folder")
		return internalDataFolder
	}
	
	/**
	 * Retrieves the application's external data directory, which is located in
	 * application-specific storage and may be accessible to other apps.
	 *
	 * This directory is suitable for storing files that the user might expect to
	 * manage through a file manager, but which are specific to the app's operation.
	 * The path is typically `/storage/emulated/0/Android/data/<package_name>/files/`.
	 *
	 * @return A [DocumentFile] representing the external storage directory, or `null` if the
	 *         directory is not available (e.g., if external storage is unmounted).
	 * @see getExternalFilesDir
	 */
	fun getExternalDataFolder(): DocumentFile? {
		logger.d("Accessing external data folder")
		return getExternalFilesDir(null)?.let { fromFile(it) }
	}
	
	/**
	 * Retrieves a `DocumentFile` representing the public downloads directory.
	 *
	 * This directory is the standard, user-accessible location for files downloaded
	 * by applications (e.g., `/storage/emulated/0/Download`). Accessing this
	 * directory often requires appropriate storage permissions from the user.
	 *
	 * @return A `DocumentFile` for the public downloads directory, or `null` if the
	 *         directory is not accessible (e.g., due to permission denial or if the
	 *         storage is unavailable).
	 * @see com.anggrayudi.storage.file.PublicDirectory.DOWNLOADS
	 */
	fun getPublicDownloadDir(): DocumentFile? {
		logger.d("Accessing public downloads directory")
		return fromPublicFolder(INSTANCE, DOWNLOADS)
	}
	
	/**
	 * Provides access to the global, app-wide settings and user preferences.
	 *
	 * This accessor returns the singleton `AIOSettings` instance, which is initialized
	 * during the critical startup phase of the application. The instance is loaded
	 * from the ObjectBox database, with a fallback to legacy storage if the database
	 * is unavailable.
	 *
	 * @return The singleton `AIOSettings` instance containing all user configurations.
	 * @see AIOSettings for details on available settings.
	 * @see AIOApp.onCreate for the initialization logic.
	 */
	fun getAIOSettings(): AIOSettings {
		logger.d("Accessing application settings")
		return aioSettings
	}
	
	/**
	 * Checks if the global application settings have been successfully loaded.
	 *
	 * This utility function provides a reliable way to determine if the `aioSettings`
	 * singleton has been initialized. It is useful for components that depend on
	 * settings being available before they can operate correctly.
	 *
	 * @return `true` if the `aioSettings` instance has been loaded and is ready for use,
	 *         `false` otherwise. This can be `false` early in the application lifecycle
	 *         or if the initialization failed.
	 * @see aioSettings for the singleton instance.
	 * @see loadSystemConfigurations for the initialization logic.
	 */
	fun isAIOSettingLoaded(): Boolean {
		return runCatching {
			return aioSettings.userSelectedUILanguage.isNotEmpty()
		}.getOrDefault(false)
	}
	
	/**
	 * Provides singleton access to the main download management system.
	 *
	 * This function returns the global `DownloadSystem` instance, which is responsible for
	 * orchestrating all download-related activities. The system is lazily initialized
	 * and pre-populated with download models from the database during app startup to ensure
	 * immediate availability and consistent state.
	 *
	 * Use this manager to:
	 * - Start, pause, resume, or cancel downloads.
	 * - Query the status and progress of ongoing or completed downloads.
	 * - Access the list of all download models.
	 *
	 * @return The singleton `DownloadSystem` instance for managing all download operations.
	 * @see DownloadSystem
	 * @see AIOApp.onCreate for initialization logic.
	 */
	fun getDownloadManager(): DownloadSystem {
		logger.d("Accessing download manager")
		return downloadSystem
	}
	
	/**
	 * Provides access to the browsing history management system.
	 *
	 * This accessor retrieves the singleton instance of `AIOHistory`, which is responsible
	 * for tracking, storing, and managing the user's browsing session records. The history
	 * is loaded from the ObjectBox database during application startup, with a fallback
	 * to legacy storage if needed.
	 *
	 * @return The singleton `AIOHistory` instance, providing access to all browsing records.
	 * @see aioHistory for the underlying singleton instance.
	 * @see AIOHistoryDBManager for database loading logic.
	 */
	fun getAIOHistory(): AIOHistory {
		logger.d("Accessing browsing history")
		return aioHistory
	}
	
	/**
	 * Provides access to the bookmarks management system, which handles all user-saved bookmarks.
	 *
	 * This function returns the singleton instance of [AIOBookmarks], responsible for creating,
	 * reading, updating, and deleting bookmarks. The underlying data is persisted in the
	 * local ObjectBox database for fast and efficient retrieval. During application startup,
	 * bookmark data is loaded from the database or a legacy storage file if the database fails.
	 *
	 * @return The [AIOBookmarks] instance containing all user bookmark collections.
	 * @see AIOBookmarks for bookmark management logic.
	 * @see AIOBookmarksDBManager for database interaction.
	 */
	fun getAIOBookmarks(): AIOBookmarks {
		logger.d("Accessing bookmarks manager")
		return aioBookmark
	}
	
	/**
	 * Provides access to the favicon management and caching system.
	 *
	 * This accessor returns the singleton instance of [AIOFavicons], which is responsible
	 * for fetching, caching, and serving website favicons. The instance is lazily
	 * initialized, ensuring it is created only when first accessed.
	 *
	 * @return The singleton [AIOFavicons] instance for website icon management.
	 * @see AIOFavicons for implementation details.
	 */
	fun getAIOFavicon(): AIOFavicons {
		logger.d("Accessing favicon manager")
		return aioFavicons
	}
	
	/**
	 * Provides access to the backend service integration and API management system.
	 *
	 * This accessor returns the singleton `AIOBackend` instance, which is responsible
	 * for handling all network communications, API interactions, and remote service
	 * integrations. The instance is lazily initialized on its first access.
	 *
	 * @return The singleton `AIOBackend` instance for all network and API operations.
	 * @see AIOBackend for implementation details.
	 */
	fun getAIOBackend(): AIOBackend {
		logger.d("Accessing backend package")
		return aioBackend
	}
	
	/**
	 * Manages a priority-based, multi-stage startup sequence for optimal application initialization.
	 *
	 * This class orchestrates the initialization process by categorizing tasks into
	 * three priority levels, ensuring that critical components are loaded first while
	 * deferring non-essential operations to background threads. This strategy minimizes
	 * the application's startup time and improves responsiveness.
	 *
	 * ### Task Categories & Execution Strategy:
	 * 1.  **Critical Tasks**: Essential for basic app functionality. Executed
	 *     sequentially and synchronously on the calling thread (`onCreate`) to guarantee
	 *     availability before any other operations proceed.
	 * 2.  **High-Priority Tasks**: Important for user-facing features but not blocking.
	 *     Executed concurrently on a CPU-bound thread pool (`Dispatchers.Default`).
	 * 3.  **Background Tasks**: Long-running or I/O-intensive operations (e.g., network
	 *     requests, file I/O). Executed concurrently on an I/O-optimized thread pool
	 *     (`Dispatchers.IO`) to avoid impacting UI performance.
	 *
	 * ### Usage Flow:
	 * - Tasks are added to their respective priority queues.
	 * - `executeCriticalTasks()` is called first to block and complete essential setup.
	 * - `executeHighPriorityTasks()` and `executeBackgroundTasks()` are then called to
	 *   launch the remaining tasks concurrently.
	 *
	 * @see CoroutineScope for managing asynchronous task execution.
	 * @see Dispatchers for providing appropriate execution contexts.
	 */
	private class StartupManager {
		
		private val logger = LogHelperUtils.from(javaClass)
		
		/**
		 * A collection of critical startup tasks that must be executed sequentially and
		 * block the main thread until completion. These tasks are essential for the
		 * application's core functionality to be established. The application will not
		 * proceed with other initializations until all tasks in this list are finished.
		 *
		 * @see StartupManager.addCriticalTask
		 * @see StartupManager.executeCriticalTasks
		 */
		private val criticalTasks = mutableListOf<() -> Unit>()
		
		/**
		 * A list of high-priority initialization tasks that are important for
		 * user-facing components but can be executed concurrently after the critical path.
		 * These tasks should complete relatively quickly to ensure a responsive UI.
		 *
		 * Examples: Loading bookmarks, browsing history, initializing lifecycle managers.
		 *
		 * @see executeHighPriorityTasks where these tasks are launched.
		 */
		private val highPriorityTasks = mutableListOf<() -> Unit>()
		
		/**
		 * A list of non-essential, long-running, or I/O-bound initialization tasks.
		 * These tasks are executed concurrently on a background thread pool (`Dispatchers.IO`)
		 * after critical and high-priority tasks have started, ensuring they do not
		 * impact application startup performance or UI responsiveness.
		 *
		 * Examples include:
		 * - Initializing third-party libraries like YouTube-DL.
		 * - Starting optional services like usage tracking.
		 * - Performing file I/O or network operations.
		 */
		private val backgroundTasks = mutableListOf<() -> Unit>()
		
		/**
		 * Coroutine scope for managing concurrent startup tasks.
		 * Uses `Dispatchers.Default` for CPU-bound high-priority tasks and can switch
		 * to `Dispatchers.IO` for background I/O-bound tasks.
		 * This scope is used to launch and manage the lifecycle of asynchronous
		 * initialization processes.
		 */
		private val startupScope = CoroutineScope(Dispatchers.Default)
		
		/**
		 * Registers a critical task to be executed during the initial startup phase.
		 *
		 * These tasks are considered essential for the application's basic functionality
		 * and will block the main thread until they are all completed. They are executed
		 * sequentially in the order they are added.
		 *
		 * Use this for initializations that must be finished before any other part of
		 * the app can safely run, such as loading settings or initializing core libraries.
		 *
		 * @param task A lambda function representing the critical initialization operation.
		 *             This function will be executed on the main thread.
		 */
		fun addCriticalTask(operation: () -> Unit) {
			logger.d("Registering critical startup operation")
			criticalTasks.add(operation)
		}
		
		/**
		 * Registers a high-priority task for concurrent execution during startup.
		 *
		 * High-priority tasks are typically for initializing user-facing components
		 * that are important but do not need to block the main thread. These tasks
		 * are executed concurrently on the `Dispatchers.Default` context, allowing for
		 * faster application startup compared to sequential execution.
		 *
		 * Example use cases:
		 * - Loading user bookmarks or browsing history from a database.
		 * - Initializing UI-related lifecycle managers.
		 *
		 * @param task A lambda function representing the high-priority initialization operation.
		 * @see executeHighPriorityTasks
		 */
		fun addHighPriorityTask(operation: () -> Unit) {
			logger.d("Registering high priority startup operation")
			highPriorityTasks.add(operation)
		}
		
		/**
		 * Registers a background task for non-essential, IO-bound initialization.
		 * These tasks run on an IO-optimized dispatcher and are suitable for operations
		 * like file I/O or network calls that should not block the main thread.
		 *
		 * @param task The lambda function representing the background initialization operation.
		 */
		fun addBackgroundTask(operation: () -> Unit) {
			logger.d("Registering background startup operation")
			backgroundTasks.add(operation)
		}
		
		/**
		 * Executes all registered critical tasks sequentially on the current thread.
		 *
		 * This method blocks the calling thread (typically the main thread during `onCreate`)
		 * until all tasks in the `criticalTasks` queue have been completed. This synchronous
		 * execution guarantees that essential, core components are fully initialized before
		 * the application proceeds with other startup activities.
		 *
		 * The tasks are executed in the order they were added.
		 *
		 * @see addCriticalTask
		 * @see criticalTasks
		 */
		fun executeCriticalTasks() {
			val taskCount = criticalTasks.size
			logger.d("Executing $taskCount critical tasks sequentially")
			criticalTasks.forEachIndexed { index, operation ->
				operation()
			}
		}
		
		/**
		 * Executes all registered high-priority tasks concurrently.
		 *
		 * This method launches the tasks on a CPU-optimized dispatcher (`Dispatchers.Default`)
		 * and returns immediately, allowing the startup process to continue while these
		 * tasks run in the background. It uses `async` to parallelize the execution
		 * and `awaitAll` to ensure all tasks complete within the coroutine scope before
		 * the scope itself finishes, guaranteeing proper completion without blocking the
		 * calling thread.
		 *
		 * @see addHighPriorityTask
		 * @see CoroutineScope.launch
		 * @see kotlinx.coroutines.async
		 * @see kotlinx.coroutines.awaitAll
		 */
		fun executeHighPriorityTasks() {
			val taskCount = highPriorityTasks.size
			logger.d("Executing $taskCount high priority tasks concurrently")
			startupScope.launch {
				highPriorityTasks.map { operation ->
					async { operation() }
				}.awaitAll()
			}
		}
		
		/**
		 * Launches all registered background tasks for concurrent execution on an I/O-optimized
		 * dispatcher. This method is non-blocking and returns immediately.
		 *
		 * Background tasks are ideal for long-running, non-essential, or I/O-bound operations
		 * that should not interfere with application startup or UI responsiveness. Each task is
		 * launched in a separate coroutine using `Dispatchers.IO`, which is specifically
		 * designed for blocking I/O operations like network requests or file system access.
		 *
		 * The method uses `async` to start each task and `awaitAll` to ensure that all
		 * tasks are managed within the `startupScope`, but it does not block the calling thread.
		 *
		 * @see addBackgroundTask To register a task for execution.
		 * @see Dispatchers.IO The coroutine dispatcher used for executing these tasks.
		 */
		fun executeBackgroundTasks() {
			val taskCount = backgroundTasks.size
			logger.d("Executing $taskCount background tasks on IO dispatcher")
			startupScope.launch {
				backgroundTasks.map { operation ->
					async(Dispatchers.IO) { operation() }
				}.awaitAll()
			}
		}
	}
}