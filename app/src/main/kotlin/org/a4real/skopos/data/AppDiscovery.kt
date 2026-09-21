package org.a4real.skopos.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.a4real.skopos.core.PolicyState
import org.a4real.skopos.core.SkoposContract

/** One row of the scopable-app list; policy/vector state resolved per package. */
data class AppRow(
    val packageName: String,
    val label: String,
    val declaresReadContacts: Boolean,
    val isSystem: Boolean,
    /** Null while the daemon is unreachable. */
    val vectorActive: Boolean?,
    /** Null while the daemon is unreachable. */
    val policy: PolicyState?,
    /** True when PackageManager could not verify the package; configuration still allowed. */
    val unverified: Boolean = false,
)

/**
 * The two discovery sources for scopable apps, neither needing QUERY_ALL_PACKAGES:
 *
 *  - Vector scope ([PolicyRepository.vectorScope]): packages Vector already injects this
 *    module into. Always relevant, no PackageManager visibility needed;
 *  - launchable apps visible through a manifest `<queries>` MAIN/LAUNCHER declaration,
 *    badged by READ_CONTACTS declaration where the (visible) package info is available.
 *
 * A launcher-discovered package is never dropped by enrichment failure (it falls back to a
 * package-name row); a per-row policy failure degrades only that row. System apps are
 * flagged, never enabled by default, and the toggle only covers the launchable set —
 * full system enumeration is not possible without QUERY_ALL_PACKAGES or daemon privilege.
 * Skopos itself is never scopable (the runtime refuses it even if mis-scoped).
 */
object AppDiscovery {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val declaresReadContacts: Boolean,
        val isSystem: Boolean,
    )

    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        // Launcher activities do not declare CATEGORY_DEFAULT, so MATCH_DEFAULT_ONLY would
        // wrongly exclude ordinary launchable apps. Flag 0 is the canonical launcher query.
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != SkoposContract.MODULE_PACKAGE }
            .distinct()
        return packages.map { pkg -> entryOrFallback(context, pkg) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Full enrichment, falling back to a package-name row when PackageInfo/ApplicationInfo
     * lookup fails: a discovered package must stay visible. Best-effort system flag means a
     * fallback row is treated as non-system (shown regardless of the toggle).
     */
    private fun entryOrFallback(context: Context, packageName: String): AppEntry = runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        val label = pm.getApplicationLabel(appInfo).toString().ifEmpty { packageName }
        val declared = runCatching {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions?.contains(Manifest.permission.READ_CONTACTS) == true
        }.getOrDefault(false)
        val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        AppEntry(packageName, label, declared, system)
    }.getOrDefault(
        AppEntry(packageName, packageName, declaresReadContacts = false, isSystem = false),
    )

    /**
     * Conservative package-name syntax check for the manual-entry fallback: dotted segments,
     * each starting with a letter. Visibility is deliberately NOT required — an unverifiable
     * package can still be configured; enforcement remains Vector's authority.
     */
    fun isValidPackageName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        val parts = name.split(".")
        if (parts.size < 2) return false
        return parts.all { part ->
            part.isNotEmpty() && part[0].isLetter() &&
                part.all { it.isLetterOrDigit() || it == '_' }
        }
    }

    /**
     * Pure list assembly, host-testable: Vector scope unioned with launcher discovery and
     * manual entries. One row's [policyFor] failure degrades only that row to unknown policy;
     * assembly never aborts. Scope-only or manual-only packages appear as unverified rows.
     */
    fun assembleRows(
        scopePackages: List<String>?,
        discovered: List<AppEntry>,
        manualPackages: Set<String>,
        showSystem: Boolean,
        policyFor: (String) -> PolicyState?,
    ): List<AppRow> {
        val byPkg = discovered.associateBy { it.packageName }
        val candidates = ((scopePackages ?: emptyList()) +
            discovered.map { it.packageName } + manualPackages)
            .distinct()
            .filter { it != SkoposContract.MODULE_PACKAGE }
        return candidates.map { candidate ->
            val info = byPkg[candidate]
            val policy = runCatching { policyFor(candidate) }.getOrNull()
            AppRow(
                packageName = candidate,
                label = info?.label ?: candidate,
                declaresReadContacts = info?.declaresReadContacts == true,
                isSystem = info?.isSystem == true,
                vectorActive = scopePackages?.contains(candidate),
                policy = policy,
                unverified = info == null,
            )
        }.filter { showSystem || !it.isSystem }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Splits assembled rows into (MANAGED, OTHER). Managed means Vector-active or holding a
     * configured policy — deliberately surfacing previously configured apps even when Vector
     * scope was removed. Alphabetical by label inside each group, package-name tiebreak.
     */
    fun groupApps(rows: List<AppRow>): Pair<List<AppRow>, List<AppRow>> {
        val byName = compareBy<AppRow> { it.label.lowercase() }.thenBy { it.packageName }
        val (managed, other) = rows.partition { row ->
            row.vectorActive == true || row.policy is PolicyState.Configured
        }
        return managed.sortedWith(byName) to other.sortedWith(byName)
    }
}
