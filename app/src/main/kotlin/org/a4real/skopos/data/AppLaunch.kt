package org.a4real.skopos.data

import android.content.IntentSender
import android.content.pm.PackageManager

/**
 * Target-app launching for the manager detail screen. Returns the front-door launch sender,
 * or null when the package has no launchable activity (callers hide the launch action).
 * No root involved.
 */
object AppLaunch {

    /** Launch sender, or null when unavailable/invalid — never throws. */
    fun launchSenderOrNull(pm: PackageManager, packageName: String): IntentSender? = runCatching {
        if (!AppDiscovery.isValidPackageName(packageName)) return null
        pm.getLaunchIntentSenderForPackage(packageName)
    }.getOrNull()
}
