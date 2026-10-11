package org.websnake.vidchain.fallback.fixes

import java.io.File

/**
 * T8.9 (inherited bug fix): the original app finds a new download's number by listing its whole files/ folder and
 * taking the highest "<n>_download.json" + 1, on every DownloadDataModel() - also for each of the N downloads it
 * loads on start, so the start cost grew with N x folder size. The highest number can only change when the folder's
 * entries change, which changes the folder's modification time: reuse the last scan while that time is the same.
 */
object MaxIdCache {
	private var stamp = Long.MIN_VALUE
	private var max = 0

	/** the next free number: [scan] (the original full listing, returning the highest number in use) runs only when [dir] changed */
	@Synchronized
	fun next(dir: File, scan: () -> Int): Int = next(dir.lastModified(), scan)

	@Synchronized
	fun next(dirStamp: Long, scan: () -> Int): Int {
		// 0 = unknown (no such folder / no timestamp): never trust a cached value then
		if (dirStamp == 0L || dirStamp != stamp) {
			max = scan()
			stamp = dirStamp
		}
		return max + 1
	}

	@Synchronized
	internal fun reset() { stamp = Long.MIN_VALUE; max = 0 }
}
