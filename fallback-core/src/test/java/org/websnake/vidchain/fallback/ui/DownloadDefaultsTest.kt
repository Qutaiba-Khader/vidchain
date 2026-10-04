package org.websnake.vidchain.fallback.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDefaultsTest {
	@Test fun onlyFreshInstallsStillOnTheOriginalDefaultOnce() {
		assertTrue(DownloadDefaults.shouldSwitch(freshInstall = true, appliedBefore = false, stillOriginalDefault = true))
		assertFalse(DownloadDefaults.shouldSwitch(freshInstall = false, appliedBefore = false, stillOriginalDefault = true))  // an update: keep
		assertFalse(DownloadDefaults.shouldSwitch(freshInstall = true, appliedBefore = true, stillOriginalDefault = true))   // once only
		assertFalse(DownloadDefaults.shouldSwitch(freshInstall = true, appliedBefore = false, stillOriginalDefault = false)) // the user chose
	}
}
