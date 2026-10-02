package app.core.engines.video_parser.parsers

import com.dslplatform.json.*
import io.objectbox.annotation.*
import java.io.*

/**
 * Represents a specific format or quality of a video.
 *
 * This data class holds all the metadata related to a single video stream, such as its
 * resolution, codecs, file size, and the direct URL for streaming or downloading. It is used to
 * represent one of the multiple formats that a single video might be available in.
 *
 * This class is designed to be serialized to JSON and stored in an ObjectBox database.
 *
 * @property id The unique identifier for this format entry in the local database.
 * @property downloadDataModelDBId A foreign key linking this format to a parent download
 * model.
 * @property isFromSocialMedia A flag indicating if the video source is a social media
 * platform.
 * @property formatId A unique identifier for the format, often provided by the source (e.g.,
 * "137", "22").
 * @property formatExtension The file extension of the video format (e.g., "mp4", "webm").
 * @property formatResolution The resolution of the video (e.g., "1920x1080", "720p").
 * @property formatFileSize The size of the file, typically as a human-readable string (e.g.,
 * "15.7MiB").
 * @property formatVcodec The video codec used for encoding (e.g., "avc1.640028", "vp9").
 * @property formatAcodec The audio codec used for encoding (e.g., "mp4a.40.2", "opus"). Can be
 * "none" for video-only streams.
 * @property formatTBR The total bitrate of the stream, often in KBit/s.
 * @property formatProtocol The network protocol used to access the stream (e.g., "https",
 * "http").
 * @property formatStreamingUrl The direct URL to stream or download this specific video
 * format.
 */
@CompiledJson
@Entity
data class VideoFormat(
	@Id @JvmField @param:JsonAttribute(name = "id")
	var id: Long = 0L,

	@JvmField @param:JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelDBId: Long = -1L,

	@JvmField @param:JsonAttribute(name = "isFromSocialMedia")
	var isFromSocialMedia: Boolean = false,

	@JvmField @param:JsonAttribute(name = "formatId")
	var formatId: String = "",

	@JvmField @param:JsonAttribute(name = "formatExtension")
	var formatExtension: String = "",

	@JvmField @param:JsonAttribute(name = "formatResolution")
	var formatResolution: String = "",

	@JvmField @param:JsonAttribute(name = "formatFileSize")
	var formatFileSize: String = "",

	@JvmField @param:JsonAttribute(name = "formatVcodec")
	var formatVcodec: String = "",

	@JvmField @param:JsonAttribute(name = "formatAcodec")
	var formatAcodec: String = "",

	@JvmField @param:JsonAttribute(name = "formatTBR")
	var formatTBR: String = "",

	@JvmField @param:JsonAttribute(name = "formatProtocol")
	var formatProtocol: String = "",

	@JvmField @param:JsonAttribute(name = "formatStreamingUrl")
	var formatStreamingUrl: String = ""
) : Serializable


/**
 * Data class representing complete video information and metadata.
 *
 * This class is designed to be persistable in an ObjectBox database (`@Entity`) and serializable
 * to/from JSON (`@CompiledJson`). It encapsulates all details parsed about a video, including its
 * title, description, thumbnails, available formats, and necessary network-related data like
 * cookies and referrers.
 *
 * @property id Unique database identifier for the VideoInfo entity.
 * @property downloadDataModelDBId Foreign key linking to an associated download model in the system.
 * @property videoTitle The main title of the video.
 * @property videoThumbnailUrl URL for the video's thumbnail image.
 * @property videoThumbnailByReferer Flag indicating if the `videoUrlReferer` is required to fetch
 * the thumbnail.
 * @property videoDescription The description or summary of the video content.
 * @property videoUrlReferer The referer URL that might be required in HTTP headers to access the
 * video stream.
 * @property videoUrl The original URL from which the video information was extracted.
 * @property videoFormats A list of [VideoFormat] objects, each representing an available quality,
 * resolution, or container format for the video.
 * @property videoCookie A string containing cookies required for authenticated access to the video
 * or its metadata.
 * @property videoDuration The total duration of the video in milliseconds.
 * @property videoCookieTempPath A temporary file system path where cookie data might be stored, for
 * instance, by external tools like yt-dlp.
 */
@CompiledJson
@Entity
data class VideoInfo(
	@Id @JvmField @param:JsonAttribute(name = "id")
	var id: Long = 0L,

	@JvmField @param:JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelDBId: Long = -1L,

	@JvmField @param:JsonAttribute(name = "videoTitle")
	var videoTitle: String? = null,

	@JvmField @param:JsonAttribute(name = "videoThumbnailUrl")
	var videoThumbnailUrl: String? = null,

	@JvmField @param:JsonAttribute(name = "videoThumbnailByReferer")
	var videoThumbnailByReferer: Boolean = false,

	@JvmField @param:JsonAttribute(name = "videoDescription")
	var videoDescription: String? = null,

	@JvmField @param:JsonAttribute(name = "videoUrlReferer")
	var videoUrlReferer: String? = null,

	@JvmField @param:JsonAttribute(name = "videoUrl")
	var videoUrl: String = "",

	@JvmField @param:JsonAttribute(name = "videoFormats")
	var videoFormats: List<VideoFormat> = emptyList(),

	@JvmField @param:JsonAttribute(name = "videoCookie")
	var videoCookie: String? = "",

	@JvmField @param:JsonAttribute(name = "videoDuration")
	var videoDuration: Long = 0L,

	@JvmField @param:JsonAttribute(name = "videoCookieTempPath")
	var videoCookieTempPath: String = ""
) : Serializable
