package org.websnake.vidchain.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

/** What Media3 downloaded: a store to read it back from, the URLs ffmpeg should open (video [+ audio]) and a cleanup. */
class Downloaded(val store: ContentStore, val inputs: List<String>, val close: () -> Unit)

/** Method M's downloader seam (Media3 in the app, fakes in tests). */
fun interface StreamDownloader {
	suspend fun download(url: String, headers: Map<String, String>, preferredHeight: Int?, dir: File, onProgress: (Float) -> Unit): Downloaded
}

/**
 * Media3's offline downloaders (already in the APK for the app's player) fetch the chosen HLS/DASH rendition into a
 * private SimpleCache (resumable, Media3's own retry and AES-key handling). Only the user's resolution is fetched.
 */
@OptIn(UnstableApi::class)
class Media3Downloader(private val context: Context) : StreamDownloader {

	override suspend fun download(url: String, headers: Map<String, String>, preferredHeight: Int?, dir: File, onProgress: (Float) -> Unit): Downloaded {
		dir.mkdirs()
		val db = StandaloneDatabaseProvider(context.applicationContext)
		val cache = SimpleCache(dir, NoOpCacheEvictor(), db)
		val close = { runCatching { cache.release() }; db.close(); dir.deleteRecursively(); Unit }
		try {
			val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
				.setUserAgent(headers["User-Agent"]).setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Cookie", true) })
			val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http)
			val dash = url.substringBefore('?').lowercase().endsWith(".mpd")
			val (downloader, inputs) = withContext(Dispatchers.IO) { if (dash) dashJob(url, factory, preferredHeight) else hlsJob(url, factory, preferredHeight) }
			run(downloader, onProgress)
			val store = ContentStore { u -> DataSourceInputStream(factory.createDataSource(), DataSpec(Uri.parse(u))) }
			return Downloaded(store, inputs, close)
		} catch (t: Throwable) {
			close(); throw t
		}
	}

	private suspend fun run(d: Downloader, onProgress: (Float) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
		cont.invokeOnCancellation { d.cancel() }
		Thread({
			try { d.download { _, _, percent -> onProgress(percent) }; cont.resumeWith(Result.success(Unit)) }
			catch (t: Throwable) { if (!cont.isCancelled) cont.resumeWith(Result.failure(t)) }
		}, "vidchain-media3").start()
	}

	/** a multivariant playlist: the variant at the user's height (and its audio rendition) as plain media playlists */
	private fun hlsJob(url: String, factory: CacheDataSource.Factory, height: Int?): Pair<Downloader, List<String>> {
		val playlist = DataSourceInputStream(factory.createDataSource(), DataSpec(Uri.parse(url))).use { HlsPlaylistParser().parse(Uri.parse(url), it) }
		if (playlist !is HlsMultivariantPlaylist) { requireVod(playlist); return HlsDownloader(MediaItem.fromUri(url), factory) to listOf(url) }
		val variants = playlist.variants.map { RenditionChoice.Option(it.url.toString(), it.format.height.takeIf { h -> h > 0 }, it.format.bitrate.takeIf { b -> b > 0 }) }
		val chosen = RenditionChoice.pick(variants, height) ?: return HlsDownloader(MediaItem.fromUri(url), factory) to listOf(url)
		val variant = playlist.variants.first { it.url.toString() == chosen.url }
		requireVod(DataSourceInputStream(factory.createDataSource(), DataSpec(variant.url)).use { HlsPlaylistParser().parse(variant.url, it) })
		val audio = playlist.audios.firstOrNull { it.groupId == variant.audioGroupId && it.url != null }?.url?.toString()
		val keys = listOfNotNull(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, playlist.variants.indexOf(variant)),
			audio?.let { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, playlist.audios.indexOfFirst { a -> a.url?.toString() == it }) })
		val item = MediaItem.Builder().setUri(url).setStreamKeys(keys).build()
		return HlsDownloader(item, factory) to listOfNotNull(chosen.url, audio)
	}

	/** Media3 downloads what a live playlist holds right now and stops: M is for VOD; live goes to F / yt-dlp */
	private fun requireVod(p: androidx.media3.exoplayer.hls.playlist.HlsPlaylist) {
		if (p is HlsMediaPlaylist && !p.hasEndTag) throw IllegalStateException("live stream: Media3 download is for VOD only")
	}

	/** DASH: the best video representation at the user's height plus the best audio, as stream keys of period 0 */
	private fun dashJob(url: String, factory: CacheDataSource.Factory, height: Int?): Pair<Downloader, List<String>> {
		val manifest = DashUtil.loadManifest(factory.createDataSource(), Uri.parse(url))
		if (manifest.dynamic) throw IllegalStateException("live stream: Media3 download is for VOD only")
		val period = manifest.getPeriod(0)
		val keys = ArrayList<StreamKey>()
		for ((type, best) in listOf(C.TRACK_TYPE_VIDEO to true, C.TRACK_TYPE_AUDIO to false)) {
			val options = period.adaptationSets.withIndex().filter { it.value.type == type }.flatMap { (ai, set) ->
				set.representations.withIndex().map { (ri, r) -> Triple(ai, ri, RenditionChoice.Option("$ai/$ri", r.format.height.takeIf { h -> h > 0 }, r.format.bitrate.takeIf { b -> b > 0 })) }
			}
			val choice = if (best) RenditionChoice.pick(options.map { it.third }, height) else options.map { it.third }.maxByOrNull { it.bitrate ?: 0 }
			options.firstOrNull { it.third == choice }?.let { keys += StreamKey(0, it.first, it.second) }
		}
		return DashDownloader(MediaItem.Builder().setUri(url).setStreamKeys(keys).build(), factory) to listOf(url)
	}
}

/** Pure choice of a rendition (JVM-tested). */
object RenditionChoice {
	data class Option(val url: String, val height: Int?, val bitrate: Int?)

	/** the tallest option at or below [height] (highest bitrate among equals); the smallest one when none fits */
	fun pick(options: List<Option>, height: Int?): Option? {
		if (options.isEmpty()) return null
		val fits = options.filter { height == null || (it.height ?: 0) <= height }
		val pool = fits.ifEmpty { listOf(options.minByOrNull { it.height ?: Int.MAX_VALUE }!!) }
		return pool.sortedWith(compareByDescending<Option> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 }).first()
	}
}
