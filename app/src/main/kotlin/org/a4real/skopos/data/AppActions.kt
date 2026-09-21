package org.a4real.skopos.data

import android.content.IntentSender
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

/**
 * Target-app actions for the manager detail screen: opening the app and force-stopping it.
 * Process execution stays here, never in composables. Package names entering the root
 * command always pass [AppDiscovery.isValidPackageName] first, so the constructed
 * `am force-stop` argument cannot carry shell metacharacters.
 */
object AppActions {

    /** Launch availability for one package; null sender means Open stays hidden. */
    fun launchSenderOrNull(pm: PackageManager, packageName: String): IntentSender? = runCatching {
        if (!AppDiscovery.isValidPackageName(packageName)) return null
        pm.getLaunchIntentSenderForPackage(packageName)
    }.getOrNull()

    /** Raw outcome of one process invocation, mapped purely to [ForceStopResult]. */
    data class RunOutcome(val exitCode: Int?, val timedOut: Boolean, val startError: Boolean)

    sealed interface ForceStopResult {
        data object Success : ForceStopResult
        /** su binary missing or the process would not start. */
        data object RootUnavailable : ForceStopResult
        /** Process ran but reported failure (denied or am error). */
        data class Denied(val exitCode: Int) : ForceStopResult
        data object Timeout : ForceStopResult
        /** Unexpected execution failure mid-run. */
        data object Failed : ForceStopResult
    }

    fun mapOutcome(outcome: RunOutcome): ForceStopResult = when {
        outcome.timedOut -> ForceStopResult.Timeout
        outcome.startError -> ForceStopResult.RootUnavailable
        (outcome.exitCode ?: -1) == 0 -> ForceStopResult.Success
        outcome.exitCode != null -> ForceStopResult.Denied(outcome.exitCode)
        else -> ForceStopResult.Failed
    }

    /** Injectable process runner so result mapping stays host-testable. */
    fun interface CommandRunner {
        fun run(argv: List<String>, timeoutMs: Long): RunOutcome
    }

    /** Single-shot `su` invocation; no persistent shell, no output reuse. */
    object ProcessRunner : CommandRunner {
        override fun run(argv: List<String>, timeoutMs: Long): RunOutcome {
            val process = try {
                ProcessBuilder(argv).redirectErrorStream(true).start()
            } catch (_: Throwable) {
                return RunOutcome(exitCode = null, timedOut = false, startError = true)
            }
            return try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    RunOutcome(exitCode = null, timedOut = true, startError = false)
                } else {
                    RunOutcome(exitCode = process.exitValue(), timedOut = false, startError = false)
                }
            } catch (_: Throwable) {
                Thread.currentThread().interrupt()
                RunOutcome(exitCode = null, timedOut = false, startError = false)
            }
        }
    }

    /**
     * Explicit root-backed force stop. Invalid package names are rejected before any
     * execution. Returns the mapped result; callers render feedback from it.
     */
    fun forceStopViaRoot(
        packageName: String,
        runner: CommandRunner = ProcessRunner,
        timeoutMs: Long = 30_000L,
    ): ForceStopResult {
        if (!AppDiscovery.isValidPackageName(packageName)) return ForceStopResult.Failed
        return mapOutcome(runner.run(listOf("su", "-c", "am force-stop $packageName"), timeoutMs))
    }
}
