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
)

/**
 * The two discovery sources for scopable apps, neither needing QUERY_ALL_PACKAGES:
 *
 *  - Vector scope ([PolicyRepository.vectorScope]): packages Vector already injects this
 *    module into. Always relevant, no PackageManager visibility needed;
 *  - launchable apps visible through a manifest `<queries>` MAIN/LAUNCHER declaration,
 *    badged by READ_CONTACTS declaration where the (visible) package info is available.
 *
 * System apps are flagged, never enabled by default, and hidden unless explicitly shown.
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
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != SkoposContract.MODULE_PACKAGE }
            .distinct()
        return packages.mapNotNull { pkg -> entryFor(context, pkg) }
            .sortedBy { it.label.lowercase() }
    }

    private fun entryFor(context: Context, packageName: String): AppEntry? = runCatching {
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
    }.getOrNull()
}
