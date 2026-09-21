package org.a4real.skopos.data

/**
 * Shared normalized search matching for app and contact lists. Pure and host-testable;
 * deliberately token-prefix only — typo/fuzzy matching can later plug into [matches]
 * without touching UI code.
 */
object SearchQuery {

    /** Trim, collapse internal whitespace runs, lowercase. */
    fun normalize(raw: String): String =
        raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    /**
     * True when every non-empty query token prefix-matches at least one candidate token.
     * An empty query matches everything. Each candidate field is normalized independently.
     */
    fun matches(query: String, vararg candidates: String?): Boolean {
        val tokens = normalize(query).split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystacks = candidates.filterNotNull().map { normalize(it).split(" ") }
        return tokens.all { token -> haystacks.any { words -> words.any { it.startsWith(token) } } }
    }
}
