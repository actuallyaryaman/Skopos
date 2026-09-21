package org.a4real.skopos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionsTest {

    @Test
    fun `outcome mapping covers all paths`() {
        assertEquals(
            AppActions.ForceStopResult.Success,
            AppActions.mapOutcome(AppActions.RunOutcome(exitCode = 0, timedOut = false, startError = false)),
        )
        assertEquals(
            AppActions.ForceStopResult.Timeout,
            AppActions.mapOutcome(AppActions.RunOutcome(exitCode = null, timedOut = true, startError = false)),
        )
        assertEquals(
            AppActions.ForceStopResult.RootUnavailable,
            AppActions.mapOutcome(AppActions.RunOutcome(exitCode = null, timedOut = false, startError = true)),
        )
        assertEquals(
            AppActions.ForceStopResult.Denied(1),
            AppActions.mapOutcome(AppActions.RunOutcome(exitCode = 1, timedOut = false, startError = false)),
        )
        assertEquals(
            AppActions.ForceStopResult.Failed,
            AppActions.mapOutcome(AppActions.RunOutcome(exitCode = null, timedOut = false, startError = false)),
        )
    }

    @Test
    fun `invalid package rejected before execution`() {
        var invoked = false
        val result = AppActions.forceStopViaRoot(
            "not a package!",
            runner = { _, _ ->
                invoked = true
                AppActions.RunOutcome(0, timedOut = false, startError = false)
            },
        )
        assertEquals(AppActions.ForceStopResult.Failed, result)
        assertTrue(!invoked)
    }

    @Test
    fun `valid package reaches runner and maps result`() {
        var seenArgv: List<String>? = null
        val result = AppActions.forceStopViaRoot(
            "com.example.app",
            runner = { argv, _ ->
                seenArgv = argv
                AppActions.RunOutcome(0, timedOut = false, startError = false)
            },
        )
        assertEquals(AppActions.ForceStopResult.Success, result)
        assertEquals(listOf("su", "-c", "am force-stop com.example.app"), seenArgv)
    }
}
