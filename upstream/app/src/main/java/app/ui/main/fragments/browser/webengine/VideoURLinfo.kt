package app.ui.main.fragments.browser.webengine

import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import java.io.Serializable

/**
 * Represents information about a video URL detected within a web page.
 *
 * This data class holds essential metadata related to a specific video stream or file
 * that can be downloaded or played. It is typically used in conjunction with video
 * detection logic in the browser engine to provide details like file type, resolution,
 * and caching status.
 *
 * @property fileUrl The actual URL of the video file. This is used for playback or download.
 * @property isM3U8 A boolean flag indicating whether the video is an M3U8 (HLS stream) format.
 * @property totalResolutions The number of available resolutions for the video, useful for M3U8 playlists.
 * @property fileResolution A string representing the resolution of this particular video file (e.g., "720p", "1080p").
 * @property infoCached An optional string to cache additional metadata or state related to the video (e.g., pre-parsed info).
 */
@CompiledJson
data class VideoUrlInfo(
	@param: JsonAttribute(name = "fileUrl")
	var fileUrl: String,

	@param: JsonAttribute(name = "isM3U8")
	var isM3U8: Boolean,

	@param: JsonAttribute(name = "totalResolutions")
	var totalResolutions: Int,

	@param: JsonAttribute(name = "fileResolution")
	var fileResolution: String,

	@param: JsonAttribute(name = "fileDuration")
	var fileDuration: Long,

	@param: JsonAttribute(name = "infoCached")
	var infoCached: String = ""
) : Serializable
