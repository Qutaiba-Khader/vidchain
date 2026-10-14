package org.websnake.vidchain.fallback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** decision 10 (owner, 2026-10-14): no master switch; at least one built method always stays on */
class MethodSwitchesTest {
	private val built = listOf("Y", "O", "A")

	@Test fun theLastMethodOnCannotBeSwitchedOff() {
		val on = mutableSetOf("O")
		assertFalse(MethodSwitches.canTurnOff("O", built) { it in on })
		on += "A"
		assertTrue(MethodSwitches.canTurnOff("O", built) { it in on })
	}

	@Test fun aMethodThatIsNotBuiltDoesNotKeepTheLastOneOn() {
		// "X" is on but not in this APK: it cannot be what stays on
		assertFalse(MethodSwitches.canTurnOff("O", built) { it == "O" || it == "X" })
	}

	@Test fun storedAllOffIsRepairedToEveryBuiltMethod() {
		assertEquals(built, MethodSwitches.repair(built) { false })
		assertEquals(emptyList<String>(), MethodSwitches.repair(built) { it == "A" })
		assertEquals(emptyList<String>(), MethodSwitches.repair(emptyList()) { false })
	}

	@Test fun aLinkWithNoSwitchedOnCapableMethodIsReported() {
		val torrentChain = listOf("A")
		assertTrue(MethodSwitches.noCapableMethod(torrentChain, built) { it != "A" })
		assertFalse(MethodSwitches.noCapableMethod(torrentChain, built) { true })
		// a capable method that is not built does not count
		assertTrue(MethodSwitches.noCapableMethod(listOf("X"), built) { true })
	}

	@Test fun theOldMasterSwitchKeyIsTheOneRepairDeletes() {
		assertEquals("fallbacks_enabled", FallbackSettings.OLD_MASTER)
	}
}
