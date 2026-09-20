package org.a4real.skopos.core

/**
 * The M0 hook-proof contract shared by the test target and the Vector runtime module.
 *
 * Both sides must agree on the package, class and method the runtime reflects over,
 * and on the marker strings, or the proof silently fails. Keeping the contract here
 * makes the two halves of the spike compile against one source of truth.
 */
object SkoposContract {
    const val TEST_PACKAGE = "org.a4real.skopos.test"
    const val PROBE_CLASS = "$TEST_PACKAGE.SkoposProbe"
    const val PROBE_METHOD = "value"
    const val ORIGINAL_RESULT = "Skopos test: original"
    const val HOOKED_RESULT = "Skopos test: hooked"
}