package org.a4real.skopos.data

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState

/**
 * Pure remembered-selection rules (host-testable, no framework): the durable LOOKUP_KEY set
 * kept alongside the active scope so FULL/EMPTY never discard it. Persistence lives in the
 * per-package daemon group under [REMEMBERED_KEY]; the codec mirrors the SELECTED line
 * format (`SELECTED` header + one key per line, sorted) with `""` for empty.
 */
object RememberedPolicy {

    const val REMEMBERED_KEY = "contact_selected_keys"

    fun encode(keys: Set<String>): String =
        if (keys.isEmpty()) "" else "SELECTED\n" + keys.sorted().joinToString("\n")

    /** Malformed input (including a bare scope word) decodes to empty — never weakens policy. */
    fun decode(raw: String?): Set<String> {
        if (raw.isNullOrEmpty()) return emptySet()
        val lines = raw.lineSequence().toList()
        if (lines.firstOrNull() != "SELECTED") return emptySet()
        return lines.drop(1).filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Effective remembered set: the stored entry when present, else the legacy fallback —
     * an existing `SELECTED(keys)` scope acts as its own remembered set so pre-patch
     * installations restore immediately without toggling first.
     */
    fun effectiveRemembered(storedPresent: Boolean, stored: Set<String>, policy: PolicyState?): Set<String> {
        if (storedPresent) return stored
        val scope = (policy as? PolicyState.Configured)?.scope as? ContactScope.Selected
        return scope?.lookupKeys ?: emptySet()
    }

    /** One atomic write: the active scope plus the remembered set it leaves behind. */
    data class PolicyWrite(val scope: ContactScope, val remembered: Set<String>)

    /** Contact toggle inside SELECTED: empty result means explicit EMPTY + cleared memory. */
    fun writeForToggle(nextKeys: Set<String>): PolicyWrite =
        if (nextKeys.isEmpty()) PolicyWrite(ContactScope.Empty, emptySet())
        else PolicyWrite(ContactScope.from(nextKeys), nextKeys)

    /** Entering FULL/EMPTY preserves the current-or-remembered selection silently. */
    fun writeForModeChange(policy: PolicyState?, remembered: Set<String>): Set<String> {
        val current = (policy as? PolicyState.Configured)?.scope as? ContactScope.Selected
        return current?.lookupKeys ?: remembered
    }

    /**
     * Tapping the SELECTED tab: with remembered keys, publish them immediately (an explicit
     * scope choice); with none, navigation-only — null means "open picker, write nothing".
     */
    fun writeForSelectTab(remembered: Set<String>): PolicyWrite? =
        if (remembered.isEmpty()) null
        else PolicyWrite(ContactScope.from(remembered), remembered)
}
