package org.a4real.skopos.core

/**
 * The durable policy picture for one target app, with absence kept distinct from an explicit
 * empty scope:
 *
 *  - [Unset]: no Skopos Contact Scope configured — enforcement is bypassed, native behavior;
 *  - [Configured]: an explicitly published scope; [corrupt] marks a present-but-unparseable
 *    stored value, which still fails closed (enforced as empty) but stays distinguishable
 *    from absence in logs and UI;
 *  - [Corrupt]: shorthand for a malformed stored value; enforced as empty, fail-closed.
 */
sealed interface PolicyState {

    /** No policy configured; the runtime must not rewrite queries. */
    data object Unset : PolicyState

    /** An explicitly published scope. */
    data class Configured(val scope: ContactScope) : PolicyState

    /** A present-but-malformed stored value; enforced as empty, fail-closed. */
    data object Corrupt : PolicyState

    companion object {

        /**
         * Reads a state from a RemotePreferences slot. [present] is whether the key exists
         * ([android.content.SharedPreferences.contains]); [encoded] is the stored value, which
         * may be null even when present.
         */
        fun decode(encoded: String?, present: Boolean): PolicyState {
            if (!present || encoded == null) return Unset
            val scope = ContactScope.decode(encoded)
            if (scope is ContactScope.Empty && encoded != "EMPTY") return Corrupt
            return Configured(scope)
        }
    }
}
