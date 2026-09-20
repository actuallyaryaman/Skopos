package org.a4real.skopos.data

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
}
