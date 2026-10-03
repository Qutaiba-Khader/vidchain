package org.websnake.vidchain.fallback.methods

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** The Python engines zip from the APK assets, unpacked once per content (file name carries its sha256). */
object PyZip {
	const val ASSET = "vidchain-py.zip"

	@Synchronized
	fun installed(context: Context): File? = runCatching {
		val bytes = context.assets.open(ASSET).use { it.readBytes() }
		val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
		val dir = File(context.noBackupFilesDir, "vidchain-tools/py").apply { mkdirs() }
		val f = File(dir, "vidchain-py-$sha.zip")
		if (!f.isFile || f.length() != bytes.size.toLong()) {
			val tmp = File(dir, f.name + ".tmp").apply { writeBytes(bytes) }
			tmp.renameTo(f)
			dir.listFiles()?.filter { it.name != f.name }?.forEach { it.delete() }   // older builds' zips
		}
		f
	}.getOrNull()
}
