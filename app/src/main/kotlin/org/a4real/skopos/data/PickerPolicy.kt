package org.a4real.skopos.data

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState

/**
 * Pure decisions for opening the SELECTED picker, kept here (core types only) so they are
 * host-testable without the framework.
 */
object PickerPolicy {

    /** What tapping the SELECTED tab may do. Never a policy write (see [decideOpenSelected]). */
    sealed interface PickerOpenAction {
        data object RequestPermission : PickerOpenAction
        data object OpenPicker : PickerOpenAction
    }

    /**
     * Opening the picker is navigation-only: it must never publish, in particular never
     * `ContactScope.from(emptySet())` (explicit EMPTY), regardless of the stored policy.
     * A SELECTED policy persists exclusively via contact toggles; an already-configured
     * selection is preserved because nothing overwrites it here. Takes the stored state only
     * to document the independence: no stored state may trigger a write on open.
     */
    fun decideOpenSelected(hasPermission: Boolean, stored: PolicyState?): PickerOpenAction =
        if (!hasPermission) PickerOpenAction.RequestPermission else PickerOpenAction.OpenPicker

    /**
     * Picker display order: checked rows first, then the rest; alphabetical by display name
     * inside each group with a lookup-key tiebreak. Search filtering happens before this
     * (caller passes the filtered list); checked-ness uses [isRowChecked] semantics via the
     * caller-supplied [isChecked] predicate so this stays free of display state.
     */
    fun sortPicker(
        contacts: List<PolicyRepository.DeviceContact>,
        isChecked: (PolicyRepository.DeviceContact) -> Boolean,
    ): List<PolicyRepository.DeviceContact> {
        val byName = compareBy<PolicyRepository.DeviceContact> { it.displayName.lowercase() }
            .thenBy { it.lookupKey }
        val (checked, rest) = contacts.partition(isChecked)
        return checked.sortedWith(byName) + rest.sortedWith(byName)
    }

    /**
     * Whether a picker row shows checked: its current lookup key is persisted, or its current
     * aggregate id was durably resolved from a persisted (possibly since-changed) key. The
     * second clause keeps a selected row checked across aggregate recreation.
     */
    fun isRowChecked(
        rowKey: String,
        rowId: Long,
        selectedKeys: Set<String>,
        resolvedIds: Set<Long>,
    ): Boolean = rowKey in selectedKeys || rowId in resolvedIds

    /**
     * Persisted keys after toggling a row: unchecking drops the row's own key plus any
     * persisted keys resolving to the same row (covers key-changed selections); checking
     * normalizes to the row's current key so no duplicates accumulate. Never invents state.
     */
    fun toggledKeys(
        selectedKeys: Set<String>,
        rowKey: String,
        rowId: Long?,
        resolved: Map<String, Long?>,
        checking: Boolean,
    ): Set<String> {
        val covered = buildSet {
            if (rowKey in selectedKeys) add(rowKey)
            if (rowId != null) {
                for ((persisted, id) in resolved) if (id == rowId) add(persisted)
            }
        }
        return if (checking) selectedKeys - covered + rowKey else selectedKeys - covered - rowKey
    }

    /**
     * One-shot picker restore on detail entry: open the picker exactly when the first
     * successful read for this session reports a persisted non-empty SELECTED scope.
     * UNSET/FULL/EMPTY/Corrupt/absent never open it; later polls must not re-drive it
     * (the caller gates on a once-per-session flag), so a user-closed picker stays closed.
     */
    fun shouldAutoOpenPicker(policy: PolicyState?): Boolean {
        val scope = (policy as? PolicyState.Configured)?.scope as? ContactScope.Selected
        return scope != null && scope.lookupKeys.isNotEmpty()
    }
}
