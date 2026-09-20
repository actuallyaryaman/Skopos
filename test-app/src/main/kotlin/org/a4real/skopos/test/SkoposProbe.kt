package org.a4real.skopos.test

import org.a4real.skopos.core.SkoposContract

/**
 * The value the Vector runtime module hooks in the M0 spike.
 *
 * The module reflects over [value] by name ([SkoposContract.PROBE_CLASS] +
 * [SkoposContract.PROBE_METHOD]) from the app's own class loader, so neither side
 * links against the other. The UI exposes the live result, making the hook state
 * visible at a glance.
 */
object SkoposProbe {

    @JvmStatic
    fun value(): String = SkoposContract.ORIGINAL_RESULT
}