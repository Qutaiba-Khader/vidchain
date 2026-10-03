package org.websnake.vidchain.fallback.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.websnake.vidchain.fallback.trace.TraceAndroid
import java.io.File

/** "Open / share trace log": the rotating trace files, oldest first, joined into one shareable file (stays on the phone until shared). */
object TraceShare {
	private const val MAX_SHARE = 4L * 1024 * 1024

	fun tail(context: Context, maxChars: Int): String {
		val all = joined(context) ?: return ""
		return if (all.length > maxChars) all.substring(all.length - maxChars) else all
	}

	fun share(context: Context): Boolean {
		val text = joined(context) ?: return false
		val dir = File(context.cacheDir, "vidchain-share").apply { mkdirs() }
		val f = File(dir, "vidchain-trace.txt")
		f.writeText(if (text.length > MAX_SHARE) text.substring((text.length - MAX_SHARE).toInt()) else text)
		val uri = FileProvider.getUriForFile(context, context.packageName + ".vidchain.files", f)
		val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		context.startActivity(Intent.createChooser(send, "Share trace log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		return true
	}

	private fun joined(context: Context): String? {
		val files = TraceAndroid.files().ifEmpty { File(context.filesDir, "trace").listFiles()?.sortedByDescending { it.lastModified() }.orEmpty() }
		if (files.isEmpty()) return null
		return files.reversed().joinToString("") { runCatching { it.readText() }.getOrDefault("") }.ifEmpty { null }
	}
}

/** own subclass so the app's own FileProvider (if any) never clashes in the merged manifest */
class VidChainFileProvider : FileProvider()
