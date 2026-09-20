package org.a4real.skopos.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SkoposContractTest {

    @Test
    fun markersAreMeaningful() {
        assertTrue(SkoposContract.ORIGINAL_RESULT.isNotBlank())
        assertTrue(SkoposContract.HOOKED_RESULT.isNotBlank())
        assertTrue("hooked must differ from original", SkoposContract.HOOKED_RESULT != SkoposContract.ORIGINAL_RESULT)
    }

    @Test
    fun probeLocationIsFullyQualified() {
        assertTrue(SkoposContract.PROBE_CLASS.startsWith(SkoposContract.TEST_PACKAGE))
        assertTrue(SkoposContract.PROBE_CLASS.endsWith(".SkoposProbe"))
        assertTrue(SkoposContract.PROBE_METHOD.isNotBlank())
    }
}