package org.websnake.vidchain.fallback.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAskTest {
	@Test fun onlyOnceAndNeverWhenAlreadyExempt() {
		assertTrue(BatteryAsk.mayAsk(exempt = false, askedBefore = false))
		assertFalse(BatteryAsk.mayAsk(exempt = true, askedBefore = false))   // already turned off: never ask
		assertFalse(BatteryAsk.mayAsk(exempt = false, askedBefore = true))   // asked once (dismissed or acted on): never again
		assertFalse(BatteryAsk.mayAsk(exempt = true, askedBefore = true))
	}
}
