package org.a4real.skopos.data

/**
 * The two things the manager can offer when [org.a4real.skopos.ui.ScopeOption.SELECTED] is
 * chosen without READ_CONTACTS.
 */
enum class ContactsRetry(val label: String) {
    REQUEST("Grant contacts access"),
    OPEN_SETTINGS("Open settings"),
}

/**
 * Pure decision logic for the manager's READ_CONTACTS gate, extracted so it is testable on
 * the host JVM without Android.
 */
object ContactsAccess {

    /**
     * Whether the manager may cross the ContactsProvider query gate: permission is granted
     * AND the user is inside the SELECTED picker. FULL/EMPTY never need the provider, so a
     * granted permission alone must not trigger a query.
     */
    fun mayQuery(granted: Boolean, inSelectedPicker: Boolean): Boolean =
        granted && inSelectedPicker

    /** The retry control offered in the SELECTED pane when the user lacks permission. */
    fun retry(deniedPermanently: Boolean): ContactsRetry =
        if (deniedPermanently) ContactsRetry.OPEN_SETTINGS else ContactsRetry.REQUEST
}