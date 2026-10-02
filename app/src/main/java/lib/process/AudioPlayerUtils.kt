package lib.process

import android.content.Context
import android.media.MediaPlayer
import java.lang.ref.WeakReference

open class AudioPlayerUtils(context: Context?) {

	private val logger = LogHelperUtils.from(javaClass)

	private val contextRef: WeakReference<Context?> = WeakReference(context)
	protected var mediaPlayer: MediaPlayer? = null

	protected var completionListenerRef: WeakReference<(() -> Unit)>? = null
	protected var errorListenerRef: WeakReference<((Int, Int) -> Unit)>? = null

	fun play(resId: Int) {
		stop()

		contextRef.get()?.let { safeContextRef ->
			mediaPlayer = MediaPlayer.create(safeContextRef, resId)?.apply {
				start()

				setOnCompletionListener {
					completionListenerRef?.get()?.invoke()
					stop()
				}

				setOnErrorListener { _, what, extra ->
					errorListenerRef?.get()?.invoke(what, extra)
					stop()
					false
				}
			}
		}
	}

	fun pause() {
		mediaPlayer?.takeIf { it.isPlaying }?.pause()
	}

	fun resume() {
		mediaPlayer?.takeIf { !it.isPlaying }?.start()
	}

	fun seekTo(positionMs: Int) {
		mediaPlayer?.seekTo(positionMs)
	}

	fun setVolume(leftVolume: Float, rightVolume: Float) {
		mediaPlayer?.setVolume(leftVolume, rightVolume)
	}

	fun getCurrentPosition(): Int {
		return mediaPlayer?.currentPosition ?: 0
	}

	fun getDuration(): Int = mediaPlayer?.duration ?: 0

	fun stop() {
		mediaPlayer?.apply {
			stop()
			release()
		}
		mediaPlayer = null
	}

	fun isPlaying(): Boolean {
		return mediaPlayer?.isPlaying == true
	}

	fun setOnCompletionListener(listener: () -> Unit) {
		completionListenerRef = WeakReference(listener)
	}

	fun setOnErrorListener(listener: (Int, Int) -> Unit) {
		errorListenerRef = WeakReference(listener)
	}
}