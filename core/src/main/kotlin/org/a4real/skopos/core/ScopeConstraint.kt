package org.a4real.skopos.core

/**
 * The SQL-side vocabulary of a contact scope, kept free of android.net.Uri and
 * ContactsContract so the runtime module and its testability never leak provider types
 * through the boundary.
 *
 * [ScopeFamily] names the lineages the provider routes in [__/tmp/opencode/ContactsProvider2.java]
 * `queryLocal`/`doQuery`. The id column is the one that exists in each family's projection
 * map and carries the aggregate contact id, so an appended WHERE fragment survives the
 * provider's `SQLiteQueryBuilder` column validation:
 *  - contacts family (`contacts`, `contacts/lookup/…`, `contacts/filter/…`, `contacts/group/…`)
 *    maps the aggregate id under `_id`.
 *  - raw_contacts family carries `contact_id`.
 *  - data family (data, data/phones, data/emails, data/filter, contacts/…/data, …/photo,
 *    …/entities) carries `contact_id`.
 *  - the phone_lookup family is the exception the provider expunges a caller selection from
 *    (CP2:7432), so it is scoped by a cursor wrapper instead; its id column is retained only
 *    for the wrapper's row filter.
 */
enum class ScopeFamily(val idColumn: String) {
    CONTACTS("_id"),
    RAW_CONTACTS("contact_id"),
    DATA("contact_id"),
    PHONES("contact_id"),
    EMAILS("contact_id"),
    PHONE_LOOKUP("contact_id");

    companion object {
        fun fromPathSegments(segments: List<String>): ScopeFamily? {
            val head = segments.firstOrNull() ?: return null
            if (head == "profile") return null
            return when (head) {
                "phone_lookup" -> PHONE_LOOKUP
                "contacts" -> contactsOrData(segments)
                "raw_contacts" -> rawContactsOrData(segments)
                "data" -> DATA
                "phones", "emails", "callables", "postals" -> DATA
                else -> null
            }
        }

        /**
         * A `contacts/…` lineage. Trailing data-flavoured segments switch the row source to the
         * data/entity views, whose id column is `contact_id`; everything else stays on the
         * contacts view (`_id`).
         */
        private fun contactsOrData(segments: List<String>): ScopeFamily? {
            val tail = segments.drop(1)
            return when {
                "stream_items" in tail -> null
                tail.any { it in DATA_LEAVES } -> DATA
                else -> CONTACTS
            }
        }

        private fun rawContactsOrData(segments: List<String>): ScopeFamily =
            if (segments.drop(1).any { it in DATA_LEAVES }) DATA else RAW_CONTACTS

        private val DATA_LEAVES = setOf("data", "photo", "entities")
    }
}

/**
 * Turns a resolved scope into an id-conjunct that merges into a contacts query's selection
 * via SQLiteQueryBuilder's `AND` fold. Ids are enforbidden-safe integer literals; no binding
 * arguments are introduced, so caller-supplied `?` placeholders keep their indices.
 */
object ScopeConstraint {

    /** SQLite's argument count is capped around 999; stay under with headroom. */
    const val MAX_IN_LIST = 500

    private const val IMPOSSIBLE_ID = -1L

    /**
     * @return `null` when the scope does not constrain [family][family.idColumn] rows
     * (Full, or a query that leaves the source untouched), otherwise a WHERE fragment that
     * is true for exactly the visible rows.
     */
    fun constraint(family: ScopeFamily, scope: ContactScope, aggregateIds: Set<Long>): String? {
        val column = family.idColumn
        return when (scope) {
            is ContactScope.Full -> null
            is ContactScope.Empty -> "$column = $IMPOSSIBLE_ID"
            is ContactScope.Selected ->
                if (aggregateIds.isEmpty()) "$column = $IMPOSSIBLE_ID"
                else aggregateIds
                    .sorted()
                    .chunked(MAX_IN_LIST)
                    .joinToString(" OR ") { chunk -> "$column IN (${chunk.joinToString(",")})" }
                    .let { "($it)" }
        }
    }

    /**
     * Merges [constraint] into a caller selection, preserving any caller-supplied clause and
     * its positional placeholders. `null` in, natural result out.
     */
    fun mergeSelection(selection: String?, constraint: String?): String? {
        if (constraint == null) return selection
        if (constraint.isEmpty()) return selection
        return if (selection.isNullOrEmpty()) constraint else "($selection) AND ($constraint)"
    }
}