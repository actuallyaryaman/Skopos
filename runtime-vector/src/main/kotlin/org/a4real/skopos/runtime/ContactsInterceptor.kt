package org.a4real.skopos.runtime

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.PhoneLookup
import io.github.libxposed.api.XposedInterface
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.ScopeConstraint
import org.a4real.skopos.core.ScopeFamily
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Intercepts every contacts-family query the target app issues through ContentResolver or
 * ContentProviderClient and narrows it to the policy scope.
 *
 * The ContactsProvider merges a caller's selection into its query through
 * SQLiteQueryBuilder's AND fold (CP2's single `doQuery` tail), so the contacts, raw_contacts
 * and data lineages are scoped purely by rewriting the selection: `(<caller>) AND (<col> IN (…))`
 * for SELECTED, `(<col> = -1)` for EMPTY, untouched for FULL. The PhoneLookup lineage is the
 * sole exception — CP2:7432 expunges caller selections for phone lookup — and is filtered by
 * the [PhoneLookupScopeCursor] wrapper instead.
 *
 * Re-entrancy: ContentResolver.query reaches the provider through ContentProviderClient.query,
 * so both are hooked; the [Reentrancy] guard makes whichever runs second pass through without
 * a second rewrite, and covers Skopos's own policy-resolution reads.
 */
internal class ContactsInterceptor(
    private val entry: XposedEntry,
    private val policy: PolicyCache,
) {

    private val hooks = CopyOnWriteArraySet<XposedInterface.HookHandle>()

    /**
     * Finds and hooks every query() method on the two query entry points. Hooking needs only the
     * target classloader, never an Application/Context: both entry classes are framework classes
     * resolvable through it. The lazy [PolicyCache.ensureInitialized] inside [onQuery] is what
     * picks up the app context on first contact; it is a no-op once initialized.
     */
    fun install(classLoader: ClassLoader) {
        listOf(
            ContentResolver::class.java,
            ContentProviderClient::class.java,
        ).forEach { owner ->
            val runtime = runCatching { classLoader.loadClass(owner.name) }
                .onFailure { e -> entry.resolvedLog("Could not load ${owner.simpleName}: ${e.message}") }
                .getOrNull() ?: return@forEach
            runtime.declaredMethods
                .filter { it.name == "query" && Cursor::class.java.isAssignableFrom(it.returnType) }
                .filter { it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Uri::class.java }
                .forEach { method ->
                    runCatching {
                        hooks += entry.hook(method).intercept { chain -> onQuery(chain) }
                    }.onFailure { e ->
                        entry.resolvedLog("hook registration failed ${owner.simpleName}.${method.name}: ${e.message}")
                    }
                }
        }
    }

    private fun onQuery(chain: XposedInterface.Chain): Any? {
        if (Reentrancy.isActive) return chain.proceed()
        return Reentrancy.withGuard {
            val args = chain.args
            val uri = args[0] as? Uri ?: return@withGuard chain.proceed()
            val family = UriClassifier.classify(uri)
                ?: return@withGuard chain.proceed()
            // Resume deferred policy initialization: ...
            policy.ensureInitialized()
            val snapshot = policy.current()

            when (family) {
                ScopeFamily.PHONE_LOOKUP -> {
                    if (snapshot.scope is ContactScope.Full) {
                        return@withGuard chain.proceed()
                    }
                    // PhoneLookup rows carry the aggregate contact id; inject it into the
                    // provider-facing projection so the wrapper can filter, then mask it.
                    val rewritten = rewritePhoneLookupProjection(args)
                    val hiddenIndex = hiddenContactIdIndex(args)
                    if (hiddenIndex != null) {
                        val cursor = chain.proceed(rewritten) as? Cursor ?: return@withGuard null
                        PhoneLookupScopeCursor(cursor, snapshot.allowedIds, hiddenIndex)
                    } else {
                        val cursor = chain.proceed() as? Cursor ?: return@withGuard null
                        PhoneLookupScopeCursor(cursor, snapshot.allowedIds)
                    }
                }

                else -> rewriteSelection(family, snapshot, args)?.let { merged ->
                    val next = ArrayList<Any?>(args.size)
                    args.indices.forEach {
                        next += if (it == merged.first) merged.second else args[it]
                    }
                    chain.proceed(next.toTypedArray())
                } ?: chain.proceed()
            }
        }
    }

    /** Returns the (argIndex, newValue) pair to swap, or null when the merge is a no-op. */
    private fun rewriteSelection(
        family: ScopeFamily,
        snapshot: PolicyCache.Snapshot,
        args: List<Any?>,
    ): Pair<Int, Any?>? {
        val constraint = ScopeConstraint.constraint(family, snapshot.scope, snapshot.allowedIds)
            ?: return null
        if (constraint.isEmpty()) return null

        if (args.isNotEmpty() && args[0] is Uri) {
            // Bundle-form query: selection lives inside the Bundle (ContentResolver.QUERY_ARG_SQL_SELECTION).
            val bundleIndex = args.indexOfFirst { it is Bundle }
            if (bundleIndex >= 0 && args[bundleIndex] is Bundle) {
                val source = args[bundleIndex] as Bundle
                val merged = ScopeConstraint.mergeSelection(
                    source.getString(ContentResolver.QUERY_ARG_SQL_SELECTION), constraint)
                if (merged != null) {
                    val copy = Bundle(source)
                    // QUERY_ARG_SQL_SELECTION is the only slot; args array inside the bundle stays index-aligned.
                    copy.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, merged)
                    return bundleIndex to copy
                }
                return null
            }
        }

        // Classic form: index 2 is always the selection string (nullable) on both entry points.
        return classicSelectionRewrite(args, constraint)
    }

    /** Rewrites the projection for PhoneLookup so CONTACT_ID reaches the wrapper. */
    private fun rewritePhoneLookupProjection(args: List<Any?>): Array<Any?> {
        val projectionIndex = indexOfProjection(args)
        if (projectionIndex < 0) return args.toTypedArray()
        val projection = args[projectionIndex] as? Array<*> ?: return args.toTypedArray()
        if (projection.contains(PhoneLookup.CONTACT_ID)) return args.toTypedArray()
        val augmented = projection.toList() + PhoneLookup.CONTACT_ID
        return args.toMutableList().also { it[projectionIndex] = augmented.toTypedArray() }.toTypedArray()
    }

    /** Position of the provider-facing CONTACT_ID column when the caller's projection omitted it. */
    private fun hiddenContactIdIndex(args: List<Any?>): Int? {
        if (args.size < 2) return null
        val projection = args[1] as? Array<*> ?: return null
        return if (projection.contains(PhoneLookup.CONTACT_ID)) null else projection.size
    }

    private fun indexOfProjection(args: List<Any?>): Int = if (args.size >= 2) 1 else -1
}