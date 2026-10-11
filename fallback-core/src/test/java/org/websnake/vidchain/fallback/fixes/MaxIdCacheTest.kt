package org.websnake.vidchain.fallback.fixes

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaxIdCacheTest {
	@Before fun clean() = MaxIdCache.reset()

	@Test fun scansOnceWhileTheFolderIsUnchanged() {
		var scans = 0
		repeat(1000) { assertEquals(8, MaxIdCache.next(42L) { scans++; 7 }) }
		assertEquals(1, scans)                      // the original listed the folder 1000 times
	}

	@Test fun rescansWhenTheFolderChanges() {
		var highest = 7; var scans = 0
		assertEquals(8, MaxIdCache.next(1L) { scans++; highest })
		highest = 8                                 // a new <8>_download.json was written: the folder time moves
		assertEquals(9, MaxIdCache.next(2L) { scans++; highest })
		assertEquals(2, scans)
	}

	@Test fun unknownTimeNeverCaches() {
		var scans = 0
		repeat(3) { MaxIdCache.next(0L) { scans++; 3 } }
		assertEquals(3, scans)
	}

	@Test fun sameAnswerAsAFullScanOnARealFolder() {
		val dir = Files.createTempDirectory("ids").toFile()
		fun scan() = dir.listFiles()!!.mapNotNull { it.name.substringBefore('_').toIntOrNull() }.maxOrNull() ?: 0
		assertEquals(1, MaxIdCache.next(dir) { scan() })
		File(dir, "5_download.json").writeText("{}")
		dir.setLastModified(dir.lastModified() + 2_000)   // what the file system does on a new entry (coarse clocks too)
		assertEquals(6, MaxIdCache.next(dir) { scan() })
		dir.deleteRecursively()
	}
}
