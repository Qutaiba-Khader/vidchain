package app.core.engines.downloader

import android.content.Context.*
import android.net.*
import androidx.core.net.*
import androidx.documentfile.provider.*
import app.core.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioDSLJsonInstance
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.downloader.DownloadModelBinaryMerger.Companion.MERGRED_DATA_MODEL_BINARY_FILENAME
import app.core.engines.fst_serializer.FSTBuilder.fstConfig
import app.core.engines.settings.*
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.video_parser.parsers.*
import com.aio.R.*
import com.anggrayudi.storage.file.*
import com.dslplatform.json.*
import io.objectbox.annotation.*
import lib.files.*
import lib.files.FileExtensions.ARCHIVE_EXTENSIONS
import lib.files.FileExtensions.DOCUMENT_EXTENSIONS
import lib.files.FileExtensions.IMAGE_EXTENSIONS
import lib.files.FileExtensions.MUSIC_EXTENSIONS
import lib.files.FileExtensions.PROGRAM_EXTENSIONS
import lib.files.FileExtensions.VIDEO_EXTENSIONS
import lib.files.FileSystemUtility.endsWithExtension
import lib.files.FileSystemUtility.isWritableFile
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.DownloaderUtils.renameIfDownloadFileExistsWithSameName
import lib.networks.DownloaderUtils.updateSmartCatalogDownloadDir
import lib.process.*
import lib.process.CopyObjectUtils.deepCopy
import lib.process.UniqueNumberUtils.getUniqueNumberForDownloadModels
import lib.texts.CommonTextUtils.getText
import lib.texts.CommonTextUtils.removeDuplicateSlashes
import java.io.*
import kotlin.jvm.Transient

/**
 * Main data model class representing a download task with comprehensive tracking and metadata.
 *
 * This class serves as the central entity for managing download operations, storing both
 * persistent state and runtime information. It supports serialization/deserialization via
 * JSON and binary formats, and integrates with ObjectBox for database persistence.
 *
 * The model tracks download progress, network statistics, file metadata, and various
 * state flags to manage the complete lifecycle of a download operation.
 */
@CompiledJson
@Entity
class DownloadDataModel : Serializable {
	
	/**
	 * Unique identifier for the ObjectBox database entity as primary key.
	 * Auto-generated value used for database indexing, relationships, and persistence.
	 * Value of 0 indicates new entity not yet persisted to database storage.
	 */
	@Id
	@JvmField
	@JsonAttribute(name = "id")
	var id: Long = 0L
	
	/**
	 * Unique identifier for the download task within application operation scope.
	 * Used for tracking download lifecycle, event correlation, and operational management.
	 * Distinct from database ID to maintain separation between storage and business logic.
	 */
	@JvmField
	@JsonAttribute(name = "downloadID")
	var downloadId: Int = 0
	
	/**
	 * Current operational status using constants defined in DownloadStatus class.
	 * Controls UI state, background service behavior, and user notification triggers.
	 * Examples: DOWNLOADING, PAUSED, COMPLETED, ERROR states for state machine management.
	 */
	@JvmField
	@JsonAttribute(name = "status")
	var status: Int = DownloadStatus.CLOSE
	
	/**
	 * Indicates if the download process is currently active and transferring data.
	 * True when download engine is actively processing and moving data from source to destination.
	 * Used for background service management, battery optimization, and activity indicators.
	 */
	@JvmField
	@JsonAttribute(name = "isRunning")
	var isRunning: Boolean = false
	
	/**
	 * Indicates if the download completed successfully with all verification checks passed.
	 * True when file transfer finished, integrity verified, and file is fully accessible.
	 * Triggers completion workflows, notifications, and transition to finished state management.
	 */
	@JvmField
	@JsonAttribute(name = "isComplete")
	var isComplete: Boolean = false
	
	/**
	 * Indicates if the download was explicitly marked for deletion by user or system process.
	 * True when download record and associated files are scheduled for permanent removal.
	 * Used for soft-delete patterns before physical deletion and recovery window provision.
	 */
	@JvmField
	@JsonAttribute(name = "isDeleted")
	var isDeleted: Boolean = false
	
	/**
	 * Indicates if the download was removed from UI visibility but retained in storage.
	 * Used for archive functionality where downloads are hidden from main view but preserved.
	 * Allows user recovery of hidden downloads through archive management interface.
	 */
	@JvmField
	@JsonAttribute(name = "isRemoved")
	var isRemoved: Boolean = false
	
	/**
	 * Flag indicating if file was saved to private/secure storage with restricted access.
	 * True when download destination is app-private directory preventing external app access.
	 * Enhances security for sensitive files and provides controlled access management.
	 */
	@JvmField
	@JsonAttribute(name = "isWentToPrivateFolder")
	var isWentToPrivateFolder: Boolean = false
	
	/**
	 * Flag indicating if the source download URL has expired or become permanently invalid.
	 * True when server returns 404, 410, or resource-not-found errors during access attempts.
	 * Triggers URL refresh mechanisms and user notifications for updated link acquisition.
	 */
	@JvmField
	@JsonAttribute(name = "isFileUrlExpired")
	var isFileUrlExpired: Boolean = false
	
	/**
	 * Flag indicating if yt-dlp encountered processing issues during media download extraction.
	 * True when yt-dlp returns non-zero exit code, parsing errors, or format detection failures.
	 * Used for fallback strategies, error reporting, and alternative download method selection.
	 */
	@JvmField
	@JsonAttribute(name = "isYtdlpHavingProblem")
	var isYtdlpHavingProblem: Boolean = false
	
	/**
	 * Detailed error message from yt-dlp containing specific processing failure information.
	 * Includes stderr output, exception details, parsing failures, or format compatibility issues.
	 * Used for user error reporting, debugging assistance, and recovery procedure guidance.
	 */
	@JvmField
	@JsonAttribute(name = "ytdlpProblemMsg")
	var ytdlpProblemMsg: String = ""
	
	/**
	 * Flag indicating if the expected destination file does not exist after reported completion.
	 * True when filesystem verification fails despite download engine reporting successful transfer.
	 * Triggers corruption detection, re-download attempts, and file recovery procedures.
	 */
	@JvmField
	@JsonAttribute(name = "isDestinationFileNotExisted")
	var isDestinationFileNotExisted: Boolean = false
	
	/**
	 * Flag indicating if file integrity verification via cryptographic checksum validation failed.
	 * True when computed file hash doesn't match expected value indicating potential corruption.
	 * Prevents use of corrupted files and triggers automatic integrity recovery mechanisms.
	 */
	@JvmField
	@JsonAttribute(name = "isFileChecksumValidationFailed")
	var isFileChecksumValidationFailed: Boolean = false
	
	/**
	 * Flag indicating download is paused waiting for network connectivity restoration.
	 * True when download suspended due to network unavailability, airplane mode, or connectivity loss.
	 * Automatically resumes transfer when stable network connection is detected and available.
	 */
	@JvmField
	@JsonAttribute(name = "isWaitingForNetwork")
	var isWaitingForNetwork: Boolean = false
	
	/**
	 * Flag indicating failure to access or read from source file location during operations.
	 * True when file permissions, storage mounting issues, or I/O errors prevent successful access.
	 * Used for storage troubleshooting, permission request flows, and alternative access methods.
	 */
	@JvmField
	@JsonAttribute(name = "isFailedToAccessFile")
	var isFailedToAccessFile: Boolean = false
	
	/**
	 * Flag indicating if URL expiration notification dialog has been displayed to user.
	 * Prevents duplicate notifications and user annoyance for known expired URL conditions.
	 * Reset when new URL is provided, download is retried, or expiration condition changes.
	 */
	@JvmField
	@JsonAttribute(name = "isExpiredURLDialogShown")
	var isExpiredURLDialogShown: Boolean = false
	
	/**
	 * Flag indicating if automatic file categorization engine has processed and classified file.
	 * True when smart categorization has analyzed file type, content, and metadata for organization.
	 * Enables intelligent storage organization, search optimization, and contextual file management.
	 */
	@JvmField
	@JsonAttribute(name = "isSmartCategoryDirProcessed")
	var isSmartCategoryDirProcessed: Boolean = false
	
	/**
	 * Message to display to user via dialog, notification, or status display mechanisms.
	 * Contains user-friendly error messages, status updates, action requests, or information alerts.
	 * Cleared after user acknowledgement, action completion, or message display timeout.
	 */
	@JvmField
	@JsonAttribute(name = "msgToShowUserViaDialog")
	var msgToShowUserViaDialog: String = ""
	
	/**
	 * Flag indicating if download was initiated from browser context or web view integration.
	 * True when download triggered via browser extension, web view intercept, or browser intent.
	 * Affects referral handling, cookie management, source tracking, and context-aware behavior.
	 */
	@JvmField
	@JsonAttribute(name = "isDownloadFromBrowser")
	var isDownloadFromBrowser: Boolean = false
	
	/**
	 * Flag indicating if basic yt-dlp metadata extraction completed successfully for media files.
	 * True when essential media metadata including title, format, duration, and quality is available.
	 * Enables media-specific features, preview generation, and informed format selection pre-download.
	 */
	@JvmField
	@JsonAttribute(name = "isBasicYtdlpModelInitialized")
	var isBasicYtdlpModelInitialized: Boolean = false
	
	/**
	 * Custom HTTP headers to include in download requests for authentication and API requirements.
	 * Key-value pairs for authentication tokens, API keys, custom parameters, or server requirements.
	 * Merged with default headers providing flexible request configuration and protocol compliance.
	 */
	@JvmField
	@JsonAttribute(name = "additionalWebHeaders")
	var additionalWebHeaders: Map<String, String>? = null
	
	/**
	 * Name of the target file being downloaded after processing and conflict resolution.
	 * Final filename determined from URL parsing, content-disposition, and user preferences.
	 * Used for storage organization, file type detection, and user interface display elements.
	 */
	@JvmField
	@JsonAttribute(name = "fileName")
	var fileName: String = ""
	
	/**
	 * Source URL from which the file is being downloaded including protocol and full path.
	 * Complete HTTP/HTTPS URL used for download initiation, resume operations, and source verification.
	 * Supports various protocols including http, https, ftp, and custom scheme implementations.
	 */
	@JvmField
	@JsonAttribute(name = "fileURL")
	var fileURL: String = ""
	
	/**
	 * HTTP Referrer header value indicating source webpage that initiated download request.
	 * Contains URL of webpage where download link was clicked for referral tracking and analytics.
	 * Used for server-side authentication, session context maintenance, and relative URL resolution.
	 */
	@JvmField
	@JsonAttribute(name = "siteReferrer")
	var siteReferrer: String = ""
	
	/**
	 * Target directory path where downloaded file will be permanently stored and organized.
	 * Absolute filesystem path representing final storage location for file preservation and access.
	 * Supports internal app storage, external shared storage, and custom directory structures.
	 */
	@JvmField
	@JsonAttribute(name = "fileDirectory")
	var fileDirectory: String = ""
	
	/**
	 * MIME type identifying file format and content type for proper handling and validation.
	 * Internet media type from server response or file extension analysis determining file nature.
	 * Used for application association, content negotiation, security checks, and type validation.
	 */
	@JvmField
	@JsonAttribute(name = "fileMimeType")
	var fileMimeType: String = ""
	
	/**
	 * Content-Disposition header value providing server-suggested filename and handling instructions.
	 * Server-provided metadata suggesting filename, download behavior, and content treatment.
	 * Overrides URL-derived filename when present and valid according to HTTP specification.
	 */
	@JvmField
	@JsonAttribute(name = "fileContentDisposition")
	var fileContentDisposition: String = ""
	
	/**
	 * Cookie string containing authentication tokens and session identifiers for secure requests.
	 * Session cookies, authentication tokens, or site-specific identifiers for protected resources.
	 * Enables access to user-specific content, personalized downloads, and authenticated services.
	 */
	@JvmField
	@JsonAttribute(name = "siteCookieString")
	var siteCookieString: String = ""
	
	/**
	 * Local filesystem path to cached thumbnail image for media files and visual preview generation.
	 * Path to locally stored thumbnail image extracted from media or generated during processing.
	 * Used for quick previews, visual identification, and enhanced user interface presentation.
	 */
	@JvmField
	@JsonAttribute(name = "thumbPath")
	var thumbPath: String = ""
	
	/**
	 * Remote URL source pointing to external thumbnail, poster, or preview image for media files.
	 * External URL providing visual representation of file content for preview and identification.
	 * Used for thumbnail downloading, cache population, and fallback visual representation.
	 */
	@JvmField
	@JsonAttribute(name = "thumbnailUrl")
	var thumbnailUrl: String = ""
	
	/**
	 * Temporary file path used during yt-dlp processing phase for intermediate storage operations.
	 * Intermediate storage location during media extraction, format conversion, and processing stages.
	 * Cleaned up automatically after successful processing completion and file reorganization.
	 */
	@JvmField
	@JsonAttribute(name = "tempYtdlpDestinationFilePath")
	var tempYtdlpDestinationFilePath: String = ""
	
	/**
	 * Temporary status information providing progress feedback during yt-dlp processing operations.
	 * Progress messages, stage information, and intermediate states from yt-dlp execution output.
	 * Provides real-time user feedback during complex media processing and extraction operations.
	 */
	@JvmField
	@JsonAttribute(name = "tempYtdlpStatusInfo")
	var tempYtdlpStatusInfo: String = ""
	
	/**
	 * URI representation of target directory location for platform-agnostic storage access methods.
	 * Content URI or file scheme URI supporting Storage Access Framework and document providers.
	 * Enables consistent storage access across different Android versions and storage providers.
	 */
	@JvmField
	@JsonAttribute(name = "fileDirectoryURI")
	var fileDirectoryURI: String = ""
	
	/**
	 * Automatically determined category name from intelligent file analysis and classification.
	 * Smart classification based on file type, content analysis, metadata, and user behavior patterns.
	 * Enables organized storage structure, intelligent search, and contextual file relationships.
	 */
	@JvmField
	@JsonAttribute(name = "fileCategoryName")
	var fileCategoryName: String = ""
	
	/**
	 * Formatted timestamp string indicating download start time in user-friendly format.
	 * Locale-aware display string showing date and time when download was initiated.
	 * Examples: "Jan 15, 2024 2:30 PM", "15/01/2024 14:30". Empty string indicates
	 * download hasn't started or timing data is unavailable.
	 */
	@JvmField
	@JsonAttribute(name = "startTimeDateInFormat")
	var startTimeDateInFormat: String = ""
	
	/**
	 * Unix timestamp in milliseconds indicating precise download start time.
	 * Machine-readable timestamp representing milliseconds since January 1, 1970 UTC.
	 * Used for duration calculations, sorting, and time-based analytics. Value of 0L
	 * indicates download not yet started or timestamp not recorded.
	 */
	@JvmField
	@JsonAttribute(name = "startTimeDate")
	var startTimeDate: Long = 0L
	
	/**
	 * Formatted timestamp string of last file modification time in user-friendly format.
	 * Display string showing when the source file was last modified on the server.
	 * Examples: "Jan 14, 2024 10:15 AM", "14/01/2024 10:15". Used for version
	 * tracking and change detection. Empty string indicates modification time unknown.
	 */
	@JvmField
	@JsonAttribute(name = "lastModifiedTimeDateInFormat")
	var lastModifiedTimeDateInFormat: String = ""
	
	/**
	 * Unix timestamp in milliseconds of last file modification on source server.
	 * Machine-readable timestamp from server's Last-Modified header or filesystem metadata.
	 * Used for cache validation, conditional downloads, and update checking.
	 * Value of 0L indicates modification time not available from server.
	 */
	@JvmField
	@JsonAttribute(name = "lastModifiedTimeDate")
	var lastModifiedTimeDate: Long = 0L
	
	/**
	 * Flag indicating if file size could not be determined from source server.
	 * True when server doesn't provide Content-Length header or returns unknown size.
	 * Affects progress calculation strategy - switches to indeterminate progress
	 * mode and disables accurate ETA calculations when true.
	 */
	@JvmField
	@JsonAttribute(name = "isUnknownFileSize")
	var isUnknownFileSize: Boolean = false
	
	/**
	 * Total file size in bytes as reported by server Content-Length header.
	 * Expected complete size of the file being downloaded. Used for progress
	 * percentage calculations, storage space verification, and download completion
	 * validation. Value of 0L indicates size unknown or not yet retrieved.
	 */
	@JvmField
	@JsonAttribute(name = "fileSize")
	var fileSize: Long = 0L
	
	/**
	 * Cryptographic hash/checksum for file integrity verification and duplication detection.
	 * Typically MD5, SHA-1, or SHA-256 hash provided by server or computed post-download.
	 * Used to verify file integrity, detect corruption, and identify duplicate files.
	 * Default "--" indicates checksum not available, not computed, or not provided by server.
	 */
	@JvmField
	@JsonAttribute(name = "fileChecksum")
	var fileChecksum: String = "--"
	
	/**
	 * Human-readable formatted string representation of file size for UI display.
	 * Automatically converted to appropriate units (B, KB, MB, GB, TB) with locale-aware
	 * formatting and decimal precision. Examples: "1.5 MB", "2.3 GB", "450 KB".
	 * Empty string indicates size calculation pending or size unknown.
	 */
	@JvmField
	@JsonAttribute(name = "fileSizeInFormat")
	var fileSizeInFormat: String = ""
	
	/**
	 * Average download speed in bytes per second calculated across entire active transfer period.
	 * Computed as totalBytesDownloaded / totalActiveTransferTime. Provides consistent
	 * performance metric unaffected by temporary network fluctuations. Used for overall
	 * connection quality assessment and historical performance analysis.
	 */
	@JvmField
	@JsonAttribute(name = "averageSpeed")
	var averageSpeed: Long = 0L
	
	/**
	 * Maximum achieved download speed in bytes per second during the download session.
	 * Tracks peak performance to measure network capability and identify optimal transfer
	 * conditions. Used for connection quality benchmarking and user performance feedback.
	 * Reset when download is restarted or resumed after significant interruption.
	 */
	@JvmField
	@JsonAttribute(name = "maxSpeed")
	var maxSpeed: Long = 0L
	
	/**
	 * Current real-time download speed in bytes per second updated frequently during active transfer.
	 * Calculated over a short time window (typically 1-3 seconds) to provide immediate
	 * feedback on network performance. Highly variable and responsive to current network
	 * conditions. Used for live progress updates and dynamic ETA adjustments.
	 */
	@JvmField
	@JsonAttribute(name = "realtimeSpeed")
	var realtimeSpeed: Long = 0L
	
	/**
	 * Formatted string representation of average download speed throughout the entire download session.
	 * Calculated as total bytes downloaded divided by total active download time. Displayed in
	 * human-readable format (e.g., "1.2 MB/s", "450 KB/s"). Default "--" indicates insufficient
	 * data for calculation or download not started.
	 */
	@JvmField
	@JsonAttribute(name = "averageSpeedInFormat")
	var averageSpeedInFormat: String = "--"
	
	/**
	 * Formatted string representation of maximum download speed achieved during the session.
	 * Tracks peak performance for analytics and user feedback. Displayed in human-readable format
	 * (e.g., "2.5 MB/s", "800 KB/s"). Default "--" indicates no speed data recorded yet.
	 */
	@JvmField
	@JsonAttribute(name = "maxSpeedInFormat")
	var maxSpeedInFormat: String = "--"
	
	/**
	 * Formatted string representation of current real-time download speed.
	 * Updated frequently (typically every 1-2 seconds) during active transfers. Displayed in
	 * human-readable format (e.g., "1.8 MB/s", "320 KB/s"). Default "--" indicates no current
	 * transfer activity or speed calculation unavailable.
	 */
	@JvmField
	@JsonAttribute(name = "realtimeSpeedInFormat")
	var realtimeSpeedInFormat: String = "--"
	
	/**
	 * Flag indicating if the download supports resumption after network interruption or pause.
	 * True when server supports byte-range requests and file supports partial downloads.
	 * Enables pause/resume functionality and recovery from network failures.
	 */
	@JvmField
	@JsonAttribute(name = "isResumeSupported")
	var isResumeSupported: Boolean = false
	
	/**
	 * Flag indicating if multi-threaded parallel downloading is supported for this file.
	 * True when server supports concurrent connections and file is large enough to benefit
	 * from chunked downloading. Enables faster downloads through parallel segment transfers.
	 */
	@JvmField
	@JsonAttribute(name = "isMultiThreadSupported")
	var isMultiThreadSupported: Boolean = false
	
	/**
	 * Total number of connection retry attempts made during resume sessions.
	 * Incremented each time the download engine attempts to re-establish connection
	 * after interruption. Used for retry limit enforcement and connection quality assessment.
	 */
	@JvmField
	@JsonAttribute(name = "resumeSessionRetryCount")
	var resumeSessionRetryCount: Int = 0
	
	/**
	 * Total number of connection retries tracked across all resume attempts.
	 * Provides comprehensive retry statistics for network reliability analysis and
	 * performance monitoring. Differs from resumeSessionRetryCount by tracking all
	 * retry events, not just per-session.
	 */
	@JvmField
	@JsonAttribute(name = "totalTrackedConnectionRetries")
	var totalTrackedConnectionRetries: Int = 0
	
	/**
	 * Download completion percentage represented as long integer (0-100).
	 * Calculated as (downloadedByte / fileSize) * 100. Used for progress bars
	 * and completion tracking. Ranges from 0 (not started) to 100 (complete).
	 */
	@JvmField
	@JsonAttribute(name = "progressPercentage")
	var progressPercentage: Long = 0L
	
	/**
	 * Formatted string representation of completion percentage for UI display.
	 * Typically includes percentage symbol and decimal precision (e.g., "45.2%", "100%").
	 * Empty string indicates progress calculation pending or unavailable.
	 */
	@JvmField
	@JsonAttribute(name = "progressPercentageInFormat")
	var progressPercentageInFormat: String = ""
	
	/**
	 * Total number of bytes successfully downloaded so far.
	 * Accumulates across all download sessions including resumes. Used for progress
	 * calculation and verification against total file size.
	 */
	@JvmField
	@JsonAttribute(name = "downloadedByte")
	var downloadedByte: Long = 0L
	
	/**
	 * Formatted string representation of downloaded bytes for user display.
	 * Converted to human-readable format with appropriate units (e.g., "45.2 MB", "1.2 GB").
	 * Default "--" indicates no bytes downloaded or calculation pending.
	 */
	@JvmField
	@JsonAttribute(name = "downloadedByteInFormat")
	var downloadedByteInFormat: String = "--"
	
	/**
	 * Array tracking starting byte positions for each download chunk in parallel transfers.
	 * Uses fixed-size array of 18 elements where each element represents the starting byte
	 * offset (0-based) for a specific chunk. Essential for range request construction and
	 * chunk boundary management.
	 */
	@JvmField
	@JsonAttribute(name = "partStartingPoint")
	var partStartingPoint: LongArray = LongArray(18)
	
	/**
	 * Array tracking ending byte positions for each download chunk in parallel transfers.
	 * Uses fixed-size array of 18 elements where each element represents the inclusive ending
	 * byte position for a specific chunk. Combined with partStartingPoint to define exact
	 * byte ranges for each parallel segment.
	 */
	@JvmField
	@JsonAttribute(name = "partEndingPoint")
	var partEndingPoint: LongArray = LongArray(18)
	
	/**
	 * Array tracking total size (in bytes) allocated to each download chunk.
	 * Uses fixed-size array of 18 elements where each element represents the total
	 * byte count assigned to a specific chunk. Used for chunk progress calculation
	 * and download distribution planning.
	 */
	@JvmField
	@JsonAttribute(name = "partChunkSizes")
	var partChunkSizes: LongArray = LongArray(18)
	
	/**
	 * Array tracking bytes successfully downloaded for each individual chunk.
	 * Uses fixed-size array of 18 elements where each element represents the
	 * cumulative bytes transferred for a specific chunk. Enables per-chunk
	 * progress tracking and identification of stalled segments.
	 */
	@JvmField
	@JsonAttribute(name = "partsDownloadedByte")
	var partsDownloadedByte: LongArray = LongArray(18)
	
	/**
	 * Array tracking completion percentage for each download chunk in parallel downloads.
	 * Uses fixed-size array of 18 elements (0-17) where each element represents the progress
	 * percentage (0-100) of an individual download segment. Enables progress visualization
	 * for multi-part downloads and resumable transfers.
	 */
	@JvmField
	@JsonAttribute(name = "partProgressPercentage")
	var partProgressPercentage: IntArray = IntArray(18)
	
	/**
	 * Total cumulative time spent actively downloading in milliseconds.
	 * Includes only the time when data transfer was occurring, excluding pauses,
	 * network interruptions, or user waiting time. Used for performance analytics
	 * and download speed calculations.
	 */
	@JvmField
	@JsonAttribute(name = "timeSpentInMilliSec")
	var timeSpentInMilliSec: Long = 0L
	
	/**
	 * Estimated remaining time to complete download in seconds.
	 * Calculated based on current download speed and remaining file size.
	 * Dynamic value that updates during active downloads. Shows "--" or 0 when
	 * estimation is unavailable or download is complete.
	 */
	@JvmField
	@JsonAttribute(name = "remainingTimeInSec")
	var remainingTimeInSec: Long = 0L
	
	/**
	 * Human-readable formatted string representation of time spent downloading.
	 * Display format varies based on duration (e.g., "45s", "2m 30s", "1h 15m").
	 * Default value "--" indicates no active download time or calculation pending.
	 */
	@JvmField
	@JsonAttribute(name = "timeSpentInFormat")
	var timeSpentInFormat: String = "--"
	
	/**
	 * Human-readable formatted string representation of estimated remaining time.
	 * Display format varies based on estimated duration (e.g., "30s", "5m", "2h").
	 * Default value "--" indicates estimation unavailable, complete, or paused.
	 */
	@JvmField
	@JsonAttribute(name = "remainingTimeInFormat")
	var remainingTimeInFormat: String = "--"
	
	/**
	 * Current status message for user display and progress tracking.
	 * Provides descriptive text about download state (e.g., "Downloading...",
	 * "Paused", "Completed", "Error: Network unavailable"). Used in UI
	 * notifications and progress dialogs.
	 */
	@JvmField
	@JsonAttribute(name = "statusInfo")
	var statusInfo: String = "--"
	
	/**
	 * Video-specific metadata container for media downloads.
	 * Transient annotation excludes from database persistence - reconstructed
	 * as needed from external sources. Contains video title, description,
	 * thumbnail URLs, and other media-specific properties.
	 */
	@io.objectbox.annotation.Transient
	@JvmField
	@JsonAttribute(name = "videoInfo")
	var videoInfo: VideoInfo? = null
	
	/**
	 * Video format and codec information for media processing.
	 * Transient annotation excludes from database persistence - typically
	 * populated during format selection phase. Contains resolution, codec,
	 * bitrate, and container format details for video downloads.
	 */
	@io.objectbox.annotation.Transient
	@JvmField
	@JsonAttribute(name = "videoFormat")
	var videoFormat: VideoFormat? = null
	
	/**
	 * Remote file metadata obtained from server or yt-dlp information extraction.
	 * Transient annotation excludes from database persistence - fetched dynamically
	 * when needed. Contains file size, available formats, duration, and other
	 * server-side file properties obtained before download initiation.
	 */
	@io.objectbox.annotation.Transient
	@JvmField
	@JsonAttribute(name = "remoteFileInfo")
	var remoteFileInfo: RemoteFileInfo? = null
	
	/**
	 * Command string used to execute the download process.
	 * Contains system commands or instructions for initiating and managing
	 * the download operation. May include parameters, URLs, and execution flags
	 * required by the download engine.
	 */
	@JvmField
	@JsonAttribute(name = "executionCommand")
	var executionCommand: String = ""
	
	/**
	 * Playback duration string for media files in formatted time representation.
	 * Used for audio and video files to display length (e.g., "02:30" for 2 minutes 30 seconds).
	 * Empty for non-media files. Format typically follows HH:MM:SS or MM:SS based on duration.
	 */
	@JvmField
	@JsonAttribute(name = "mediaFilePlaybackDuration")
	var mediaFilePlaybackDuration: String = ""
	
	/**
	 * Synchronization status indicator for cloud backup integration.
	 * True when the download data model has been successfully synced to cloud storage,
	 * false when pending sync or cloud backup is disabled. Used to manage data
	 * consistency across devices and prevent duplicate cloud entries.
	 */
	@JvmField
	@JsonAttribute(name = "isSyncToCloudBackup")
	var isSyncToCloudBackup: Boolean = false
	
	/**
	 * User interaction tracker for file access monitoring.
	 * True if the user has opened or accessed the downloaded file at least once,
	 * false for unopened downloads. Used for analytics, user behavior tracking,
	 * and potentially for highlighting new/unviewed content in the UI.
	 */
	@JvmField
	@JsonAttribute(name = "hasUserOpenedTheFile")
	var hasUserOpenedTheFile: Boolean = false
	
	/**
	 * Snapshot of global application settings captured when download was initiated (transient).
	 * Preserves configuration state at download creation time for consistent behavior across sessions.
	 * Deep copy ensures settings isolation; ID reset to 0L prevents database conflicts.
	 */
	@io.objectbox.annotation.Transient
	@JvmField
	@JsonAttribute(name = "globalSettings")
	var globalSettings: AIOSettings = (deepCopy(aioSettings) ?: aioSettings).apply { id = 0L }
	
	/**
	 * Companion object containing shared constants and utilities for DownloadDataModel class.
	 * This object holds transient properties and constant values used across all instances
	 * of DownloadDataModel for consistent file naming, storage, and logging.
	 */
	companion object {
		
		/**
		 * Transient logger instance shared across all DownloadDataModel instances.
		 * Marked as @Transient to exclude from serialization since logger instances
		 * should not be persisted and recreated upon deserialization.
		 */
		@Transient
		var logger = LogHelperUtils.from(DownloadDataModel::class.java)
		
		// Constants for file naming and storage
		
		/**
		 * Key used for identifying download model in intent extras or shared preferences
		 * during activity transitions, background operations, and inter-process communication.
		 */
		const val DOWNLOAD_MODEL_ID_KEY = "DOWNLOAD_MODEL_ID_KEY"
		
		/**
		 * File extension for JSON-formatted download model files storing serialized download data.
		 * Format: {downloadId}_download.json for human-readable persistence and manual inspection.
		 */
		const val DOWNLOAD_MODEL_FILE_JSON_EXTENSION = "_download.json"
		
		/**
		 * File extension for binary-formatted download model files with optimized storage.
		 * Format: {downloadId}_download.dat for efficient serialization and faster read/write operations.
		 */
		const val DOWNLOAD_MODEL_FILE_BINARY_EXTENSION: String = "_download.dat"
		
		/**
		 * File extension for cookie files storing authentication data for download sessions.
		 * Format: {downloadId}_cookies.txt preserving session cookies for resume and retry operations.
		 */
		const val DOWNLOAD_MODEL_COOKIES_EXTENSION = "_cookies.txt"
		
		/**
		 * File extension for download thumbnail images generated for media file previews.
		 * Format: {downloadId}_download.jpg for consistent thumbnail naming and cache management.
		 */
		const val THUMB_EXTENSION = "_download.jpg"
		
		/**
		 * File extension for temporary download files created during active transfer operations.
		 * These files are created during active downloads and removed upon completion or cancellation.
		 */
		const val TEMP_EXTENSION = ".aio_download"
		
		/**
		 * Converts a JSON file to a DownloadDataModel instance, with fallback to binary format if available.
		 * This method attempts to load the download model using the following priority:
		 * 1. First tries to load from the corresponding binary file (.dat) for better performance
		 * 2. Falls back to JSON deserialization if binary file is missing, invalid, or corrupted
		 * 3. Updates storage after successful load to ensure data consistency
		 *
		 * The method automatically handles corrupted binary files by deleting them and falling back to JSON.
		 *
		 * @param downloadDataModelJSONFile The JSON file containing the download model data
		 * @return DownloadDataModel instance if successful, null if both binary and JSON loading fail
		 */
		@JvmStatic
		fun convertJSONStringToClass(downloadDataModelJSONFile: File): DownloadDataModel? {
			logger.d("Starting JSON to class conversion for file: ${downloadDataModelJSONFile.absolutePath}")
			val internalDir = INSTANCE.filesDir
			
			// Generate corresponding binary filename by replacing extension with .dat
			val downloadDataModelBinaryFileName = "${downloadDataModelJSONFile.nameWithoutExtension}.dat"
			val downloadDataModelBinaryFile = File(internalDir, downloadDataModelBinaryFileName)
			
			try {
				var downloadDataModel: DownloadDataModel? = null
				var isBinaryFileValid = false
				
				// First attempt: Try to load from binary file for better performance
				if (downloadDataModelBinaryFile.exists()) {
					logger.d("Found binary download model file: ${downloadDataModelBinaryFile.name}")
					val absolutePath = downloadDataModelBinaryFile.absolutePath
					
					logger.d("Attempting to load binary from: $absolutePath")
					val objectInMemory = loadFromBinary(downloadDataModelBinaryFile)
					
					if (objectInMemory != null) {
						logger.d("Binary load successful for file: ${downloadDataModelBinaryFile.name}")
						downloadDataModel = objectInMemory
						// Update storage to ensure binary and JSON formats are synchronized
						downloadDataModel.updateInStorage()
						isBinaryFileValid = true
					} else {
						logger.d("Binary load failed for file: ${downloadDataModelBinaryFile.name}")
					}
				}
				
				// Second attempt: Fall back to JSON if binary loading failed or file doesn't exist
				if (!isBinaryFileValid || downloadDataModel == null) {
					logger.d("Attempting JSON load for file: ${downloadDataModelJSONFile.name}")
					val jsonString = downloadDataModelJSONFile.readText(Charsets.UTF_8)
					
					logger.d("JSON content length: ${jsonString.length} chars")
					val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
					downloadDataModel = aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)
					
					if (downloadDataModel != null) {
						logger.d("JSON load successful for file: ${downloadDataModelJSONFile.name}")
						// Update storage to create/update the binary version for future faster loading
						downloadDataModel.updateInStorage()
					} else {
						logger.e("Failed to parse JSON for file: ${downloadDataModelJSONFile.name}")
					}
				}
				
				return downloadDataModel
			} catch (error: Exception) {
				logger.e("Error in conversion: ${error.message}", error)
				try {
					// Clean up potentially corrupted binary file to prevent future loading issues
					downloadDataModelBinaryFile.delete()
					logger.d("Deleted potentially corrupted binary file")
				} catch (error: Exception) {
					logger.e("Failed to delete binary file", error)
				}
				return null
			}
		}
		
		@JvmStatic
		fun convertToClassFromJSON(jsonString: String): DownloadDataModel? {
			logger.d("JSON content length: ${jsonString.length} chars")
			val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
			return aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)
		}
		
		/**
		 * Loads a DownloadDataModel instance from a binary file using FST deserialization.
		 * This method attempts to read and deserialize a binary file containing a previously
		 * saved DownloadDataModel. If the file is corrupted or invalid, it will be deleted
		 * automatically to prevent future loading attempts.
		 *
		 * The method performs the following steps:
		 * 1. Checks if the binary file exists at the specified path
		 * 2. Reads the entire file content as a byte array
		 * 3. Uses FST configuration to deserialize the bytes back into a DownloadDataModel object
		 * 4. Handles corruption by deleting the problematic file
		 *
		 * @param downloadDataModelBinaryFile The File object pointing to the binary file to load from
		 * @return Deserialized DownloadDataModel instance if successful, null if file doesn't exist
		 *         or deserialization fails
		 */
		private fun loadFromBinary(downloadDataModelBinaryFile: File): DownloadDataModel? {
			logger.d("Starting binary load from: ${downloadDataModelBinaryFile.absolutePath}")
			
			// Verify that the binary file exists before attempting to load
			if (!downloadDataModelBinaryFile.exists()) {
				logger.d("Binary file not found at: ${downloadDataModelBinaryFile.absolutePath}")
				return null
			}
			
			return try {
				// Read the entire binary file content into a byte array
				logger.d("Reading binary file content")
				val bytes = downloadDataModelBinaryFile.readBytes()
				logger.d("Binary file size: ${bytes.size} bytes")
				
				// Deserialize the byte array back into a DownloadDataModel object using FST
				val result = fstConfig.asObject(bytes).apply {
					logger.d("Binary deserialization completed")
				} as DownloadDataModel
				logger.d("Binary load successful")
				result
			} catch (error: Exception) {
				// Handle deserialization errors by logging and cleaning up the corrupted file
				logger.e("Binary load error: ${error.message}", error)
				try {
					downloadDataModelBinaryFile.delete()
					logger.d("Deleted corrupted binary file")
				} catch (error: Exception) {
					logger.e("Failed to delete corrupted binary file", error)
				}
				null
			}
		}
	}
	
	/**
	 * Primary constructor initialization block for DownloadDataModel.
	 * This block is executed when a new instance of DownloadDataModel is created.
	 * It performs the initial setup by resetting all properties to their default values
	 * and ensures the model starts in a clean, consistent state.
	 *
	 * The initialization process:
	 * 1. Logs the creation of a new download model instance
	 * 2. Calls resetToDefaultValues() to initialize all properties with appropriate defaults
	 * 3. Sets up a unique download ID and configures default file directory based on settings
	 */
	init {
		logger.d("Initializing new DownloadDataModel")
		resetToDefaultValues()
	}
	
	/**
	 * Updates the download model in persistent storage with the current state.
	 * This synchronized method ensures thread-safe persistence of the download model
	 * by saving it in both binary and JSON formats, along with associated cookies
	 * and database records.
	 *
	 * The method performs the following operations in background:
	 * 1. Validates that the model has sufficient data (filename or URL) to be saved
	 * 2. Saves any available cookies for authenticated downloads
	 * 3. Cleans up transient properties before persistence
	 * 4. Saves the model in binary format for efficient storage
	 * 5. Saves the model in JSON format for readability and compatibility
	 * 6. Updates database records with the current model state
	 *
	 * Note: This method is typically called when download state changes significantly
	 * or when the app needs to persist current progress.
	 */
	@Synchronized
	fun updateInStorage() {
		logger.d("Starting storage update for download ID: $downloadId")
		ThreadsUtility.executeInBackground(codeBlock = {
			// Validate that the model has essential data before saving
			if (fileName.isEmpty() && fileURL.isEmpty()) {
				logger.d("Empty filename and URL, skipping update")
				return@executeInBackground
			}
			
			// Prepare the model for persistence by saving cookies and cleaning transient data
			logger.d("Saving cookies and cleaning model before storage")
			saveCookiesIfAvailable()
			cleanTheModelBeforeSavingToStorage()
			
			// Save the model in binary format for efficient storage and quick retrieval
			logger.d("Saving to binary format")
			saveToBinaryFormat("$downloadId$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")
			
			// Save the model in JSON format for readability and cross-platform compatibility
			logger.d("Saving to JSON format")
			val json = convertClassToJSON()
			logger.d("JSON content length: ${json.length} chars")
			
			saveStringToInternalStorage("$downloadId$DOWNLOAD_MODEL_FILE_JSON_EXTENSION", json)
			
			// Update database records to maintain consistency across storage layers
			DownloadModelsDBManager.saveDownloadWithRelationsInDB(this)
			logger.d("Storage update completed for download ID: $downloadId")
		}, errorHandler = { error ->
			logger.e("Storage update failed for download ID: $downloadId", error)
		})
	}
	
	/**
	 * Saves the current download model to a binary file using FST (Fast Serialization) configuration.
	 * This synchronized method ensures thread-safe serialization and file operations when
	 * persisting the download model to binary format for efficient storage and retrieval.
	 *
	 * The method performs the following operations:
	 * 1. Deletes any existing binary file for this download ID to ensure clean state
	 * 2. Serializes the current object to byte array using FST configuration
	 * 3. Writes the serialized bytes to a private file in the app's internal storage
	 *
	 * @param fileName The name of the binary file to save the model to
	 */
	@Synchronized
	private fun saveToBinaryFormat(fileName: String) {
		try {
			logger.d("Saving to binary file: $fileName")
			val internalDir = AIOApp.internalDataFolder
			
			// Find and delete any existing binary file for this download ID
			val modelBinaryFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")
			
			if (isWritableFile(modelBinaryFile)) {
				modelBinaryFile?.delete()?.let { isDeletedSuccessful ->
					if (isDeletedSuccessful) logger.d("Deleted existing binary file successfully")
					else logger.d("Failed to delete existing binary file")
				}
			}
			
			// Create new binary file and serialize the model object
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				// Serialize the current object to byte array using FST configuration
				val bytes = fstConfig.asByteArray(this)
				logger.d("Serialized binary size: ${bytes.size} bytes")
				
				// Write the serialized bytes to the file
				fos.write(bytes)
				logger.d("Binary save successful for file: $fileName")
			}
		} catch (error: Exception) {
			logger.e("Binary save error for file: $fileName", error)
		}
	}
	
	/**
	 * Completely deletes all files and data associated with this download model from disk and database.
	 * This synchronized method ensures thread-safe deletion of all download-related files including:
	 * - Model data files (JSON and binary formats)
	 * - Thumbnail images
	 * - Cookie files
	 * - Temporary download files
	 * - The actual downloaded file (in private folder)
	 * - Database records and relations
	 *
	 * The deletion is performed on a background thread to avoid blocking the UI and includes
	 * comprehensive error handling and logging for each deletion operation.
	 */
	@Synchronized
	fun deleteModelFromDisk() {
		logger.d("Starting model deletion for download ID: $downloadId")
		ThreadsUtility.executeInBackground(codeBlock = {
			val internalDir = AIOApp.internalDataFolder
			
			// Identify all files associated with this download model
			val mergredBinaryFile = internalDir.findFile(MERGRED_DATA_MODEL_BINARY_FILENAME)
			val modelJsonFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_FILE_JSON_EXTENSION")
			val modelBinaryFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")
			val cookieFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_COOKIES_EXTENSION")
			val thumbFile = internalDir.findFile("$downloadId$THUMB_EXTENSION")
			
			// Delete JSON model file with writable check
			logger.d("Deleting JSON file")
			isWritableFile(modelJsonFile).let {
				if (it) modelJsonFile?.delete()?.let { logger.d("Deleted JSON file successfully") }
			}
			
			// Delete binary model file with writable check
			logger.d("Deleting binary file")
			isWritableFile(modelBinaryFile).let {
				if (it) modelBinaryFile?.delete()?.let { logger.d("Deleted binary file successfully") }
			}
			
			// Delete thumbnail file with writable check
			logger.d("Deleting thumbnail file")
			isWritableFile(thumbFile).let {
				if (it) thumbFile?.delete()?.let { logger.d("Deleted thumbnail file successfully") }
			}
			
			// Delete cookie file with writable check
			logger.d("Deleting cookies file")
			isWritableFile(cookieFile).let {
				if (it) cookieFile?.delete()?.let { logger.d("Deleted cookies file successfully") }
			}
			
			// Delete merged binary file (if exists) with writable check
			logger.d("Deleting Merged binary file")
			isWritableFile(mergredBinaryFile).let {
				if (it) mergredBinaryFile?.delete()?.let { logger.d("Deleted Merged binary file successfully") }
			}
			
			// Delete all temporary files created during download process
			logger.d("Deleting temporary files")
			deleteAllTempDownloadedFiles(internalDir)
			
			// Delete the actual downloaded file if stored in private folder
			if (globalSettings.defaultDownloadLocation == PRIVATE_FOLDER) {
				logger.d("Deleting downloaded file from private folder")
				val downloadedFile = getDestinationDocumentFile()
				isWritableFile(downloadedFile).let { isDeletedSuccessful ->
					if (isDeletedSuccessful) downloadedFile.delete().let {
						logger.d("Deleted downloaded file successfully")
					}
				}
			}
			
			// Remove all database records associated with this download
			DownloadModelsDBManager.deleteDownloadWithRelations(this)
			logger.d("Model deletion completed for download ID: $downloadId")
		}, errorHandler = { error ->
			logger.e("Deletion error for download ID: $downloadId", error)
		})
	}
	
	/**
	 * Retrieves the file path of the saved cookies file if it exists and is available.
	 * This method checks if cookies have been saved for this download and returns
	 * the absolute file path to the cookies file, which can be used by download
	 * tools or libraries that require cookie authentication.
	 *
	 * The method first verifies that cookies are actually available in the model
	 * before checking for the existence of the physical cookie file.
	 *
	 * @return Absolute path to the cookies file if available, null if no cookies
	 *         are available or the file doesn't exist
	 */
	fun getCookieFilePathIfAvailable(): String? {
		// Check if the model contains any cookie data
		if (siteCookieString.isEmpty()) {
			logger.d("No cookies available for download ID: $downloadId")
			return null
		}
		
		// Generate the expected cookie file name using download ID and cookies extension
		val cookieFileName = "$downloadId$DOWNLOAD_MODEL_COOKIES_EXTENSION"
		val internalDir = AIOApp.internalDataFolder
		val cookieFile = internalDir.findFile(cookieFileName)
		
		// Return the absolute path if the cookie file exists, otherwise return null
		return if (cookieFile != null && cookieFile.exists()) {
			logger.d("Found cookies file for download ID: $downloadId")
			cookieFile.getAbsolutePath(INSTANCE)
		} else {
			logger.d("No cookies file found for download ID: $downloadId")
			null
		}
	}
	
	/**
	 * Saves the site cookies to internal storage in Netscape format if available.
	 * This method persists cookies associated with the download to enable authenticated
	 * downloads and resume capabilities. Cookies are stored in a file named with the
	 * download ID and a cookies extension.
	 *
	 * The method provides an override option to force saving even if a cookie file
	 * already exists, which is useful for updating expired or changed cookies.
	 *
	 * @param shouldOverride If true, will overwrite existing cookie file;
	 *                       if false, will skip saving if file already exists
	 */
	fun saveCookiesIfAvailable(shouldOverride: Boolean = false) {
		// Check if there are any cookies to save
		if (siteCookieString.isEmpty()) {
			logger.d("No cookies to save for download ID: $downloadId")
			return
		}
		
		// Generate the cookie file name using download ID and cookies extension
		val cookieFileName = "$downloadId$DOWNLOAD_MODEL_COOKIES_EXTENSION"
		val internalDir = AIOApp.internalDataFolder
		val cookieFile = internalDir.findFile(cookieFileName)
		
		// Skip saving if file already exists and override is not requested
		if (!shouldOverride && cookieFile != null && cookieFile.exists()) {
			logger.d("Cookies file already exists and override not requested for download ID: $downloadId")
			return
		}
		
		// Proceed with saving the cookies to internal storage
		logger.d("Saving cookies for download ID: $downloadId")
		saveStringToInternalStorage(
			fileName = cookieFileName,
			data = generateNetscapeFormattedCookieString(siteCookieString)
		)
		logger.d("Cookies saved successfully for download ID: $downloadId")
	}
	
	/**
	 * Converts a standard HTTP cookie string into Netscape-style cookie file format.
	 * This method transforms cookies from the common "name=value; name2=value2" format
	 * into the Netscape HTTP Cookie File format used by many download tools and browsers.
	 *
	 * The Netscape format includes:
	 * - Domain (left empty in this implementation)
	 * - Flag indicating if all hosts within the domain can access the cookie
	 * - Path where the cookie is valid
	 * - Secure flag (FALSE for non-HTTPS cookies)
	 * - Expiration timestamp (set to distant future)
	 * - Cookie name and value
	 *
	 * @param cookieString The original cookie string in standard HTTP format
	 * @return Formatted string in Netscape HTTP Cookie File format
	 */
	private fun generateNetscapeFormattedCookieString(cookieString: String): String {
		logger.d("Generating Netscape formatted cookie string")
		
		// Split the cookie string into individual cookies and trim whitespace
		val cookies = cookieString.split(";").map { it.trim() }
		
		// Define fixed values for Netscape cookie format
		val domain = ""  // Empty domain for broad applicability
		val path = "/"   // Root path for maximum accessibility
		val secure = "FALSE"  // Non-HTTPS cookie
		val expiry = "2147483647"  // Distant future expiration (year 2038)
		
		// Build the Netscape-formatted cookie file content
		val stringBuilder = StringBuilder()
		
		// Add file header with generation information
		stringBuilder.append("# Netscape HTTP Cookie File\n")
		stringBuilder.append("# This file was generated by the app.\n\n")
		
		// Process each cookie and convert to Netscape format
		for (cookie in cookies) {
			val parts = cookie.split("=", limit = 2)
			if (parts.size == 2) {
				val name = parts[0].trim()
				val value = parts[1].trim()
				
				// Append cookie in Netscape format: domain, flag, path, secure, expiry, name, value
				stringBuilder.append("$domain\tFALSE\t$path\t$secure\t$expiry\t$name\t$value\n")
			}
		}
		
		logger.d("Generated Netscape cookie string with ${cookies.size} cookies")
		return stringBuilder.toString()
	}
	
	/**
	 * Converts the current DownloadDataModel instance to a JSON string representation.
	 * This method uses the DSL-JSON library to serialize the object into JSON format,
	 * which can be stored in persistent storage or transmitted over network.
	 *
	 * @return JSON string representation of the current DownloadDataModel instance
	 */
	fun convertClassToJSON(): String {
		logger.d("Converting class to JSON for download ID: $downloadId")
		val outputStream = ByteArrayOutputStream()
		aioDSLJsonInstance.serialize(this, outputStream) // write to stream
		return outputStream.toByteArray().decodeToString() // convert to String
	}
	
	/**
	 * Converts a JSON string back into a DownloadDataModel instance.
	 * This method performs deserialization using the DSL-JSON library to recreate
	 * the object from its JSON representation, typically used when loading from storage.
	 *
	 * @param jsonString The JSON string to convert back to DownloadDataModel
	 * @return DownloadDataModel instance if deserialization succeeds, null otherwise
	 */
	private fun convertJSONStringToClass(jsonString: String): DownloadDataModel? {
		logger.d("Converting JSON to download data model object")
		val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
		return aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)
	}
	
	/**
	 * Gets the temporary directory used for storing incomplete download files.
	 * This directory is used to store partial downloads and temporary files during
	 * the download process, separate from the final destination directory.
	 *
	 * @return File object representing the temporary download directory
	 */
	fun getTempDestinationDir(): File {
		logger.d("Getting temp destination directory for download ID: $downloadId")
		return File("${fileDirectory}.temp/")
	}
	
	/**
	 * Creates a DocumentFile object representing the final destination file.
	 * This method constructs the complete file path and creates a DocumentFile
	 * wrapper, which is useful for working with the Storage Access Framework
	 * and provides additional file management capabilities.
	 *
	 * @return DocumentFile object pointing to the final download destination
	 */
	fun getDestinationDocumentFile(): DocumentFile {
		val destinationPath = removeDuplicateSlashes("$fileDirectory/$fileName")
		logger.d("Getting destination DocumentFile for path: $destinationPath")
		return DocumentFile.fromFile(File(destinationPath!!))
	}
	
	/**
	 * Creates and returns a File object representing the final destination path for the download.
	 * This method constructs the complete file path by combining the directory and filename,
	 * then ensures the path is properly formatted by removing any duplicate slashes.
	 *
	 * @return File object pointing to the final download destination
	 */
	fun getDestinationFile(): File {
		val destinationPath = removeDuplicateSlashes("$fileDirectory/$fileName")
		logger.d("Getting destination File for path: $destinationPath")
		return File(destinationPath!!)
	}
	
	/**
	 * Creates and returns a File object representing the temporary download file.
	 * This method generates a temporary file path by appending a temporary extension
	 * to the final destination file path. The temporary file is used during the
	 * download process and renamed to the final filename upon completion.
	 *
	 * @return File object pointing to the temporary download file
	 */
	fun getTempDestinationFile(): File {
		val tempFilePath = "${getDestinationFile().absolutePath}${TEMP_EXTENSION}"
		logger.d("Getting temp destination file: $tempFilePath")
		return File(tempFilePath)
	}
	
	/**
	 * Retrieves the URI of the thumbnail image associated with this download.
	 * This method constructs the thumbnail filename using the download ID and
	 * searches for it in the app's internal data folder.
	 *
	 * @return Uri of the thumbnail file if found, null otherwise
	 */
	fun getThumbnailURI(): Uri? {
		val thumbFilePath = "$downloadId$THUMB_EXTENSION"
		logger.d("Getting thumbnail URI for file: $thumbFilePath")
		return AIOApp.internalDataFolder.findFile(thumbFilePath)?.uri
	}
	
	/**
	 * Clears the cached thumbnail file for this download.
	 * This method attempts to delete the thumbnail file from storage and updates
	 * the model to reflect that no thumbnail is available. Useful for cleaning up
	 * temporary thumbnails or when regenerating thumbnails.
	 */
	fun clearCachedThumbnailFile() {
		logger.d("Clearing cached thumbnail for download ID: $downloadId")
		try {
			val thumbnailUri = getThumbnailURI()
			if (thumbnailUri != null) {
				thumbnailUri.toFile().delete()
				logger.d("Deleted thumbnail file successfully")
			} else {
				logger.d("No thumbnail file to delete")
			}
			// Clear the thumbnail path reference and persist the change
			thumbPath = ""
			updateInStorage()
			logger.d("Thumbnail cleared successfully for download ID: $downloadId")
		} catch (error: Exception) {
			logger.e("Error clearing thumbnail for download ID: $downloadId", error)
		}
	}
	
	/**
	 * Returns the resource ID of the default thumbnail drawable to display when no custom thumbnail is available.
	 * This method provides a fallback image for downloads that don't have custom thumbnails
	 * or when thumbnail loading fails.
	 *
	 * @return Resource ID of the default "no thumbnail available" drawable
	 */
	fun getThumbnailDrawableID(): Int {
		logger.d("Getting default thumbnail drawable ID")
		return drawable.image_no_thumb_available
	}
	
	/**
	 * Generates a comprehensive download status string for display in the UI.
	 * This method creates appropriate status information based on the download type (video vs. non-video)
	 * and current download state, handling special cases for video downloads with yt-dlp integration.
	 *
	 * For video downloads, it provides specialized status handling:
	 * - CLOSE status: Shows waiting, preparing, or failure messages when applicable
	 * - Active status: Shows either normal progress or yt-dlp specific status information
	 *
	 * For non-video downloads, it falls back to standard status information.
	 *
	 * @return Formatted string containing the appropriate download status information
	 */
	fun generateDownloadInfoInString(): String {
		logger.d("Generating download info string for download ID: $downloadId")
		
		// Handle video downloads with yt-dlp integration
		if (videoFormat != null && videoInfo != null) {
			return if (status == DownloadStatus.CLOSE) {
				// For closed/inactive video downloads, check for special status conditions
				val waitingToJoin = getText(string.title_waiting_to_join).lowercase()
				val preparingToDownload = getText(string.title_preparing_download).lowercase()
				val downloadFailed = getText(string.title_download_io_failed).lowercase()
				
				// Return special status messages for waiting, preparing, or failed states
				if (statusInfo.lowercase().startsWith(waitingToJoin) ||
					statusInfo.lowercase().startsWith(preparingToDownload) ||
					statusInfo.lowercase().startsWith(downloadFailed)
				) {
					logger.d("Returning special status info")
					statusInfo
				} else {
					// Fall back to normal status info for other closed states
					normalDownloadStatusInfo()
				}
			} else {
				// For active video downloads, choose between normal status and yt-dlp specific status
				val currentStatus = getText(string.title_started_downloading).lowercase()
				if (!statusInfo.lowercase().startsWith(currentStatus)) {
					logger.d("Returning normal download status info")
					normalDownloadStatusInfo()
				} else {
					logger.d("Returning yt-dlp status info")
					tempYtdlpStatusInfo
				}
			}
		} else {
			// For non-video downloads, use standard status information
			logger.d("Returning normal download status info (non-video)")
			return normalDownloadStatusInfo()
		}
	}
	
	/**
	 * Determines the appropriate category name for the file based on its extension.
	 * This method categorizes files into predefined types (Images, Videos, Sounds, etc.)
	 * by checking the file extension against known extension lists.
	 *
	 * The method supports two naming modes:
	 * 1. With AIO prefix: Returns category names prefixed with "AIO" (e.g., "AIO Images")
	 * 2. Without AIO prefix: Returns generic category names (e.g., "Images")
	 *
	 * @param shouldRemoveAIOPrefix If true, returns generic category names without "AIO" prefix
	 * @return The appropriate category name string based on file extension and prefix preference
	 */
	fun getUpdatedCategoryName(shouldRemoveAIOPrefix: Boolean = false): String {
		logger.d("Getting updated category name for file: $fileName")
		if (shouldRemoveAIOPrefix) {
			// Return category names without "AIO" prefix for generic display
			val categoryName = when {
				endsWithExtension(fileName, IMAGE_EXTENSIONS) -> getText(string.title_images)
				endsWithExtension(fileName, VIDEO_EXTENSIONS) -> getText(string.title_videos)
				endsWithExtension(fileName, MUSIC_EXTENSIONS) -> getText(string.title_sounds)
				endsWithExtension(fileName, DOCUMENT_EXTENSIONS) -> getText(string.title_documents)
				endsWithExtension(fileName, PROGRAM_EXTENSIONS) -> getText(string.title_programs)
				endsWithExtension(fileName, ARCHIVE_EXTENSIONS) -> getText(string.title_archives)
				else -> getText(string.title_aio_others)
			}
			logger.d("Category name (no prefix): $categoryName")
			return categoryName
		} else {
			// Return category names with "AIO" prefix for app-specific display
			val categoryName = when {
				endsWithExtension(fileName, IMAGE_EXTENSIONS) -> getText(string.title_aio_images)
				endsWithExtension(fileName, VIDEO_EXTENSIONS) -> getText(string.title_aio_videos)
				endsWithExtension(fileName, MUSIC_EXTENSIONS) -> getText(string.title_aio_sounds)
				endsWithExtension(fileName, DOCUMENT_EXTENSIONS) -> getText(string.title_aio_documents)
				endsWithExtension(fileName, PROGRAM_EXTENSIONS) -> getText(string.title_aio_programs)
				endsWithExtension(fileName, ARCHIVE_EXTENSIONS) -> getText(string.title_aio_archives)
				else -> getText(string.title_aio_others)
			}
			logger.d("Category name (with prefix): $categoryName")
			return categoryName
		}
	}
	
	/**
	 * Generates a human-readable formatted string representation of the file size.
	 * This method converts the raw byte count into a user-friendly format (e.g., KB, MB, GB)
	 * with appropriate unit suffixes for display in the UI.
	 *
	 * Handles special cases where file size is unknown or invalid:
	 * - Returns "Unknown" for files with size <= 1 byte or explicitly marked as unknown
	 * - Uses FileSizeFormatter for proper formatting of valid file sizes
	 *
	 * @return Formatted file size string (e.g., "1.5 MB") or "Unknown" for invalid sizes
	 */
	fun getFormattedFileSize(): String {
		logger.d("Getting formatted file size for download ID: $downloadId")
		return if (fileSize <= 1 || isUnknownFileSize) {
			logger.d("File size unknown")
			getText(string.title_unknown_size)
		} else {
			val formattedSize = FileSizeFormatter.humanReadableSizeOf(fileSize.toDouble())
			logger.d("Formatted file size: $formattedSize")
			formattedSize
		}
	}
	
	/**
	 * Extracts the file extension from the file name.
	 * This method parses the file name to determine the file type based on the extension
	 * (the part after the last dot in the filename).
	 *
	 * Examples:
	 * - "document.pdf" returns "pdf"
	 * - "image.jpeg" returns "jpeg"
	 * - "file.with.dots.txt" returns "txt"
	 * - "file_without_extension" returns empty string
	 *
	 * @return The file extension in lowercase, or empty string if no extension is found
	 */
	fun getFileExtension(): String {
		val extension = fileName.substringAfterLast('.', "")
		logger.d("File extension for $fileName: $extension")
		return extension
	}
	
	/**
	 * Refreshes the download folder path based on current user settings.
	 * This method updates the file directory according to the user's preferred download location
	 * setting, ensuring files are saved to the correct storage location.
	 *
	 * The method handles two main download location options:
	 * 1. PRIVATE_FOLDER: Uses app-specific external or internal storage
	 * 2. SYSTEM_GALLERY: Uses system-defined gallery/download folder
	 *
	 * This is typically called when user changes download location preferences
	 * or when initializing download settings to ensure consistency.
	 */
	fun refreshUpdatedDownloadFolder() {
		logger.d("Refreshing download folder based on user settings")
		
		// Determine download directory based on user's preferred location setting
		when (globalSettings.defaultDownloadLocation) {
			PRIVATE_FOLDER -> {
				// Attempt to use external private data folder for app-specific storage
				val externalDataFolderPath = INSTANCE.getExternalDataFolder()?.getAbsolutePath(INSTANCE)
				if (!externalDataFolderPath.isNullOrEmpty()) {
					fileDirectory = externalDataFolderPath
					logger.d("Set file directory to external private folder: $externalDataFolderPath")
				} else {
					// Fallback to internal app storage if external storage is unavailable
					val internalDataFolderPath = INSTANCE.dataDir.absolutePath
					fileDirectory = internalDataFolderPath
					logger.d(
						"External folder unavailable, " +
							"set file directory to internal storage: $internalDataFolderPath"
					)
				}
			}
			
			SYSTEM_GALLERY -> {
				// Use default system gallery folder for publicly accessible downloads
				val galleryPath = getText(string.text_default_aio_download_folder_path)
				fileDirectory = galleryPath
				logger.d("Set file directory to system gallery: $galleryPath")
			}
			
			else -> logger.d("Unknown download location, keeping previous fileDirectory: $fileDirectory")
		}
	}
	
	/**
	 * Deletes all temporary files associated with the current download.
	 * This method cleans up temporary files created during the download process,
	 * including yt-dlp temporary files and cookie files, to free up storage space.
	 *
	 * The method handles two types of temporary files:
	 * 1. yt-dlp temporary download files (identified by filename prefix)
	 * 2. Video cookie temporary files (used for authenticated video downloads)
	 *
	 * @param internalDir The DocumentFile directory where temporary files are stored
	 *
	 * Note: This method is particularly important for video downloads that may create
	 * multiple temporary files during the download and processing phases.
	 */
	private fun deleteAllTempDownloadedFiles(internalDir: DocumentFile) {
		logger.d("Deleting all temp files for download ID: $downloadId")
		try {
			// Only process temporary files for video downloads
			if (videoFormat != null && videoInfo != null) {
				// Delete yt-dlp temporary files that match the filename pattern
				if (tempYtdlpDestinationFilePath.isNotEmpty()) {
					val tempYtdlpFileName = File(tempYtdlpDestinationFilePath).name
					logger.d("Processing yt-dlp temp files with prefix: $tempYtdlpFileName")
					
					// Iterate through all files in the directory and delete matching temp files
					internalDir.listFiles().forEach { file ->
						try {
							file?.let {
								// Only process files (not directories) that match the temp file pattern
								if (!file.isFile) return@let
								if (file.name!!.startsWith(tempYtdlpFileName)) {
									file.delete()
									logger.d("Deleted temp file: ${file.name}")
								}
							}
						} catch (error: Exception) {
							logger.e("Error deleting temp file", error)
						}
					}
				}
				
				// Delete temporary cookie file used for video authentication
				if (videoInfo!!.videoCookieTempPath.isNotEmpty()) {
					val tempCookieFile = File(videoInfo!!.videoCookieTempPath)
					if (tempCookieFile.isFile && tempCookieFile.exists()) {
						tempCookieFile.delete()
						logger.d("Deleted temp cookie file: ${tempCookieFile.absolutePath}")
					}
				}
			}
			logger.d("Temp files deletion completed for download ID: $downloadId")
		} catch (error: Exception) {
			logger.e("Error deleting temp files for download ID: $downloadId", error)
		}
	}
	
	/**
	 * Generates a formatted status string for normal download scenarios.
	 * This method creates user-friendly status information displaying download progress,
	 * speed, and remaining time based on the current download state.
	 *
	 * Handles two main cases:
	 * 1. Video downloads: Shows simplified status with video-specific information
	 * 2. Regular downloads: Shows detailed progress including file size, speed, and ETA
	 *
	 * Special consideration for CLOSE status and network waiting states where
	 * speed and time information may not be available or relevant.
	 *
	 * @return Formatted string containing download status information for UI display
	 */
	private fun normalDownloadStatusInfo(): String {
		logger.d("Generating normal download status info for download ID: $downloadId")
		val textDownload = getText(string.title_downloaded)
		
		// Handle video downloads separately with simplified status format
		if (videoFormat != null && videoInfo != null) {
			val infoString = "$statusInfo  |  $textDownload ($progressPercentage%)" +
				"  |  --/s  |  --:-- "
			logger.d("Generated video download status: $infoString")
			return infoString
		} else {
			// For regular downloads, include detailed progress information
			val totalFileSize = fileSizeInFormat
			
			// Determine download speed display - show "--/s" for closed or inactive downloads
			val downloadSpeedInfo = if (status == DownloadStatus.CLOSE) "--/s"
			else realtimeSpeedInFormat
			
			// Determine remaining time display - show "--:--" for closed or network waiting states
			val remainingTimeInfo = if (status == DownloadStatus.CLOSE ||
				isWaitingForNetwork
			) "--:--" else remainingTimeInFormat
			
			val downloadingStatus = getText(string.title_started_downloading).lowercase()
			
			// Choose format based on whether download has actually started
			val result = if (statusInfo.lowercase().startsWith(downloadingStatus)) {
				// Format for actively downloading state
				"$progressPercentageInFormat% Of $totalFileSize  |  " +
					"$downloadSpeedInfo  |  $remainingTimeInfo"
			} else {
				// Format for other states (paused, queued, etc.)
				"$statusInfo  |  $textDownload ($progressPercentage%)  |  " +
					"$downloadSpeedInfo |  $remainingTimeInfo"
			}
			logger.d("Generated normal download status: $result")
			return result
		}
	}
	
	/**
	 * Resets all model properties to their default values for a new download.
	 * This method initializes a fresh download state with a unique ID and appropriate
	 * file directory based on the user's download location preferences.
	 *
	 * The method handles two main download location scenarios:
	 * 1. PRIVATE_FOLDER: Uses app's external or internal storage
	 * 2. SYSTEM_GALLERY: Uses system-defined download folder
	 *
	 * This should be called when creating a new download instance to ensure
	 * proper initialization before starting the download process.
	 */
	private fun resetToDefaultValues() {
		// Log the start of reset operation
		logger.d("Resetting to default values for new download")
		
		// Generate and assign a unique identifier for this download model
		downloadId = getUniqueNumberForDownloadModels()
		logger.d("Assigned new download ID: $downloadId")
		
		// Set file directory based on user's preferred download location setting
		if (aioSettings.defaultDownloadLocation == PRIVATE_FOLDER) {
			// Attempt to use external storage first for private app data
			val externalDataFolderPath = INSTANCE.getExternalDataFolder()?.getAbsolutePath(INSTANCE)
			if (!externalDataFolderPath.isNullOrEmpty()) {
				fileDirectory = externalDataFolderPath
				logger.d("Set file directory to external: $externalDataFolderPath")
			} else {
				// Fall back to internal storage if external is unavailable
				val internalDataFolderPath = INSTANCE.dataDir.absolutePath
				fileDirectory = internalDataFolderPath
				logger.d("Set file directory to internal: $internalDataFolderPath")
			}
		} else if (aioSettings.defaultDownloadLocation == SYSTEM_GALLERY) {
			// Use system gallery/downloads folder for publicly accessible files
			val externalDataFolderPath = getText(string.text_default_aio_download_folder_path)
			fileDirectory = externalDataFolderPath
			logger.d("Set file directory to system gallery: $externalDataFolderPath")
		}
		
		// Log completion of reset operation with current settings
		logger.d(
			"Reset completed for download ID: $downloadId " +
				"with settings: ${globalSettings.defaultDownloadLocation}"
		)
	}
	
	/**
	 * Moves completed download to app-private storage location.
	 * Copies file to secure directory and updates database records.
	 */
	fun moveToPrivateFolder(onError: (String) -> Unit, onSuccess: () -> Unit) {
		ThreadsUtility.executeInBackground(codeBlock = {
			logger.d("moveToPrivateFolder: Starting migration for $fileName")
			
			// Validate download is complete
			if (status != DownloadStatus.COMPLETE) {
				logger.d("moveToPrivateFolder: Download not complete - status: $status")
				ThreadsUtility.executeOnMain { onError.invoke("Download is not completed.") }
				return@executeInBackground
			}
			
			// Update storage location to private
			globalSettings.defaultDownloadLocation = PRIVATE_FOLDER
			val oldDestinationFile = getDestinationFile()
			
			// Select private storage path (external preferred, internal fallback)
			val externalDataFolderPath = INSTANCE.getExternalDataFolder()?.getAbsolutePath(INSTANCE)
			fileDirectory = if (!externalDataFolderPath.isNullOrEmpty()) {
				logger.d("moveToPrivateFolder: Using external storage: $externalDataFolderPath")
				externalDataFolderPath
			} else {
				logger.d("moveToPrivateFolder: Using internal storage: ${INSTANCE.dataDir.absolutePath}")
				INSTANCE.dataDir.absolutePath
			}
			
			// Update database and handle file naming conflicts
			updateSmartCatalogDownloadDir(downloadModel = this)
			renameIfDownloadFileExistsWithSameName(downloadModel = this)
			
			// Perform file migration
			val newDestinationFile = getDestinationFile()
			oldDestinationFile.copyTo(newDestinationFile, overwrite = true)
			oldDestinationFile.delete()
			
			// Update storage records
			updateInStorage()
			
			logger.d("moveToPrivateFolder: Successfully migrated $fileName to private storage")
			ThreadsUtility.executeOnMain { onSuccess.invoke() }
		})
	}
	
	/**
	 * Moves completed download to system gallery folder for public access.
	 * Copies file to system downloads/gallery directory and updates records.
	 */
	fun moveToSysGalleryFolder(onError: (String) -> Unit, onSuccess: () -> Unit) {
		ThreadsUtility.executeInBackground(codeBlock = {
			logger.d("moveToSysGalleryFolder: Starting migration for $fileName")
			
			// Validate download is complete
			if (status != DownloadStatus.COMPLETE) {
				logger.d("moveToSysGalleryFolder: Download not complete - status: $status")
				ThreadsUtility.executeOnMain { onError.invoke("Download is not completed.") }
				return@executeInBackground
			}
			
			// Update storage location to system gallery
			globalSettings.defaultDownloadLocation = SYSTEM_GALLERY
			val oldDestinationFile = getDestinationFile()
			
			// Use system gallery/downloads folder for public access
			val externalDataFolderPath = getText(string.text_default_aio_download_folder_path)
			fileDirectory = externalDataFolderPath
			logger.d("moveToSysGalleryFolder: Set directory to system gallery: $externalDataFolderPath")
			
			// Update database and handle file naming conflicts
			updateSmartCatalogDownloadDir(downloadModel = this)
			renameIfDownloadFileExistsWithSameName(downloadModel = this)
			
			// Perform file migration
			val newDestinationFile = getDestinationFile()
			oldDestinationFile.copyTo(newDestinationFile, overwrite = true)
			oldDestinationFile.delete()
			
			// Update storage records
			updateInStorage()
			
			logger.i("moveToSysGalleryFolder: Successfully migrated $fileName to system gallery")
			ThreadsUtility.executeOnMain { onSuccess.invoke() }
		})
	}
	
	/**
	 * Cleans up the download model by resetting transient properties before saving to persistent storage.
	 * This ensures that only persistent state is saved, while runtime/temporary values are cleared.
	 *
	 * The method handles two main scenarios:
	 * 1. Active downloads: Skips cleanup to preserve current progress state
	 * 2. Completed downloads: Finalizes progress values to indicate completion
	 *
	 * Note: This method should only be called when persisting the model to storage, not during active downloads.
	 */
	private fun cleanTheModelBeforeSavingToStorage() {
		// Log the cleanup operation for debugging and tracking purposes
		logger.d("Cleaning model before saving to storage for download ID: $downloadId")
		
		// Skip cleanup if download is currently running and in DOWNLOADING state
		// This preserves real-time progress information for active downloads
		if (isRunning && status == DownloadStatus.DOWNLOADING) {
			logger.d("Download is running, skipping cleanup")
			return
		}
		
		// Reset real-time speed metrics as these are transient and shouldn't be persisted
		realtimeSpeed = 0L
		realtimeSpeedInFormat = "--"
		
		// For completed downloads, finalize all progress metrics to indicate completion
		if (isComplete && status == DownloadStatus.COMPLETE) {
			// Set remaining time to zero for completed downloads
			remainingTimeInSec = 0
			remainingTimeInFormat = "--:--"
			
			// Set progress to 100% for the main download
			progressPercentage = 100L
			progressPercentageInFormat = getText(string.title_100_percentage)
			
			// Finalize downloaded bytes to match total file size
			downloadedByte = fileSize
			downloadedByteInFormat = getHumanReadableFormat(downloadedByte)
			
			// Update progress for all individual parts/chunks to 100%
			partProgressPercentage.forEachIndexed { index, _ ->
				partProgressPercentage[index] = 100
				partsDownloadedByte[index] = partChunkSizes[index]
			}
			
			// Log successful cleanup for completed download
			logger.d("Model cleaned for completed download ID: $downloadId")
		}
	}
}