package lib.process

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import java.lang.ref.WeakReference

class VideoAsAudioPlayerUtils(private val context: Context?) : AudioPlayerUtils(context) {

	fun playFromUri(uri: Uri) {
		stop()

		WeakReference(context).get()?.let { safeContext ->
			mediaPlayer = MediaPlayer().apply {
				setDataSource(safeContext, uri)
				setOnPreparedListener { start() }
				setOnCompletionListener {
					completionListenerRef?.get()?.invoke()
					stop()
				}
				setOnErrorListener { _, what, extra ->
					errorListenerRef?.get()?.invoke(what, extra)
					stop()
					false
				}
				prepareAsync()
			}
		}
	}
}
