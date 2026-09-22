package org.a4real.skopos.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.a4real.skopos.core.PolicyState
import org.a4real.skopos.core.SkoposContract

/** One row of the scopable-app list; policy/vector state resolved per package. */
data class AppRow(
    val packageName: String,
    val label: String,
    val declaresReadContacts: Boolean,
    /** Null when the grant lookup genuinely fails; never an inclusion filter. */
    val readContactsGranted: Boolean? = null,
    val isSystem: Boolean,
    /** Null while the daemon is unreachable. */
    val vectorActive: Boolean?,
    /** Null while the daemon is unreachable. */
    val policy: PolicyState?,
    /** True when PackageManager could not verify the package; configuration still allowed. */
    val unverified: Boolean = false,
    /** Downscaled launcher icon loaded once during discovery; null renders a placeholder. */
    val icon: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    /** Managed = Vector-active or holding a configured policy; always visible. */
    val isManaged: Boolean
        get() = vectorActive == true || policy is PolicyState.Configured
}

/**
 * Application discovery for permission-relevant scoping. The package universe is complete
 * installed-package enumeration (needs QUERY_ALL_PACKAGES); launchability never gates
 * inclusion. Managed/configured packages always surface regardless of metadata.
 */
object AppDiscovery {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val declaresReadContacts: Boolean,
        val readContactsGranted: Boolean? = null,
        val isSystem: Boolean,
        val icon: androidx.compose.ui.graphics.ImageBitmap? = null,
    )

    /**
     * Fast package inventory: one PackageManager call, no per-package work. Names render
     * immediately; metadata follows progressively via [enrichEntry].
     */
    fun discoverPackageNames(context: Context): List<String> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        }.getOrDefault(emptyList())
            .map { it.packageName }
            .filter { it != SkoposContract.MODULE_PACKAGE }
            .distinct()
    }

    /**
     * Permission-oriented enumeration: every installed package (no launcher requirement),
     * enriched with metadata. [permission] selects which declaration is badged; only its
     * READ_CONTACTS use is wired today. Needs QUERY_ALL_PACKAGES for a complete inventory;
     * without it the result silently degrades to the visible set.
     */
    fun discoverAppsForPermission(context: Context, permission: String): List<AppEntry> {
        val pm = context.packageManager
        val packages = runCatching {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        }.getOrDefault(emptyList())
            .map { it.packageName }
            .filter { it != SkoposContract.MODULE_PACKAGE }
            .distinct()
        return packages.map { pkg -> enrichEntry(context, pkg, permission) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Full enrichment for one package, falling back to a package-name row when
     * PackageInfo/ApplicationInfo lookup fails: a discovered package must stay visible.
     * Best-effort system flag means a fallback row is treated as non-system (shown
     * regardless of the toggle). Icon decode happens here, once per package (cached).
     */
    fun enrichEntry(
        context: Context,
        packageName: String,
        permission: String = Manifest.permission.READ_CONTACTS,
    ): AppEntry = runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        val label = pm.getApplicationLabel(appInfo).toString().ifEmpty { packageName }
        val declared = runCatching {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)
        val granted = runCatching {
            pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
        }.getOrNull()
        val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        AppEntry(packageName, label, declared, granted, system, AppIconCache.get(context, packageName))
    }.getOrDefault(
        AppEntry(packageName, packageName, declaresReadContacts = false, readContactsGranted = null, isSystem = false),
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
        alwaysInclude: Set<String> = emptySet(),
    ): List<AppRow> {
        val byPkg = discovered.associateBy { it.packageName }
        val candidates = ((scopePackages ?: emptyList()) +
            discovered.map { it.packageName } + manualPackages)
            .distinct()
            .filter { it != SkoposContract.MODULE_PACKAGE }
        return candidates.map { candidate ->
            val info = byPkg[candidate]
            val policy = runCatching { policyFor(candidate) }.getOrNull()
            val vectorActive = scopePackages?.contains(candidate)
            val managed = vectorActive == true || policy is PolicyState.Configured
            AppRow(
                packageName = candidate,
                label = info?.label ?: candidate,
                declaresReadContacts = info?.declaresReadContacts == true,
                readContactsGranted = info?.readContactsGranted,
                isSystem = info?.isSystem == true,
                vectorActive = vectorActive,
                policy = policy,
                unverified = info == null,
                icon = info?.icon,
            )
        }.filter { row ->
            // Managed rows are always visible (existing configuration must never become
            // inaccessible), as are manually added packages (explicit user intent covers
            // unverifiable cases) and packages still awaiting enrichment (placeholders
            // must paint first; relevance filtering applies once metadata lands).
            // Unmanaged, enriched rows need READ_CONTACTS relevance; the system toggle
            // only hides unmanaged system apps.
            row.isManaged || row.packageName in manualPackages ||
                row.packageName in alwaysInclude ||
                (row.declaresReadContacts && (showSystem || !row.isSystem))
        }.sortedBy { it.label.lowercase() }
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
