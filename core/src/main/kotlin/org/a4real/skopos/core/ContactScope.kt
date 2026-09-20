package org.a4real.skopos.core

/**
 * The durable contact scope for one target app: which of the device contacts it may see.
 *
 * Realm of one invariant: [Selected] can never hold an empty lookup-key set. An empty
 * selection collapses to [Empty] instead, so a consumer of [ContactScope] never has to
 * special-case "selected but nothing selected". [Full] is reachable only through the
 * caller explicitly choosing it.
 *
 * The aggregate identity of a contact is its lookup key; aggregate ids are ephemeral and
 * are resolved against the ContactsProvider at policy load time, never stored here.
 */
sealed class ContactScope {

    /** All contacts are visible; native behaviour is untouched. */
    data object Full : ContactScope()

    /** No contact is visible; every contacts-family query collapses to zero rows. */
    data object Empty : ContactScope()

    /** Only contacts whose lookup key is listed are visible; the set is never empty. */
    data class Selected(val lookupKeys: Set<String>) : ContactScope() {
        init {
            require(lookupKeys.isNotEmpty()) { "Selected scope must hold a non-empty set of lookup keys" }
        }
    }

    companion object {

        /** Produces a [Selected] for a non-empty [keys]; an empty [keys] collapses to [Empty]. */
        fun from(keys: Set<String>): ContactScope =
            if (keys.isEmpty()) Empty else Selected(keys)

        /**
         * A transport format safe for SharedPreferences and vector RemotePreferences: a mode
         * line followed by one lookup key per line. Lookup keys are safe arbitrary bytes, so a
         * record separator that is not printable brackets the set.
         */
        fun decode(encoded: String?): ContactScope {
            if (encoded.isNullOrEmpty()) return Empty
            return when (val mode = encoded.substringBefore('\n')) {
                FULL -> Full
                EMPTY -> Empty
                SELECTED -> {
                    val keys = encoded.lineSequence().drop(1).filter { it.isNotEmpty() }.toSet()
                    from(keys)
                }

                else -> Empty
            }
        }
    }

    fun encode(): String = when (this) {
        is Full -> FULL
        is Empty -> EMPTY
        is Selected -> SELECTED + "\n" + lookupKeys.joinToString("\n")
    }
}

private const val FULL = "FULL"
private const val EMPTY = "EMPTY"
private const val SELECTED = "SELECTED"