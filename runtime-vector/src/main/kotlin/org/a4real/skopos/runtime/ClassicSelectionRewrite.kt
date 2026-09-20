package org.a4real.skopos.runtime

import org.a4real.skopos.core.ScopeConstraint

/**
 * Pure slot rule for the classic query overloads on both ContentResolver and
 * ContentProviderClient (`query(Uri, String[], String, String[], String[, CancellationSignal])`).
 *
 * The selection argument is fixed at index 2 (nullable); sortOrder lives at index 4 and must
 * never be inspected or rewritten here. Merges the caller selection with [constraint] and
 * returns the replacement pair for index 2 only, or null when there is nothing to swap.
 */
internal fun classicSelectionRewrite(args: List<Any?>, constraint: String?): Pair<Int, Any?>? {
    if (args.size > 2 && (args[2] == null || args[2] is String)) {
        val callerSelection = args[2] as String?
        val merged = ScopeConstraint.mergeSelection(callerSelection, constraint) ?: return null
        return if (merged == callerSelection) null else 2 to merged
    }
    return null
}
