package org.a4real.skopos.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.a4real.skopos.BuildConfig

/**
 * About-screen content. Values originate from private build metadata
 * (`.local/skopos-about.txt` injected as BuildConfig fields at build time) and are
 * never hard-coded here; blank entries simply hide their rows.
 */
data class AboutMetadata(
    val versionName: String,
    val versionCode: Long,
    val developerName: String,
    val developerHandle: String,
    val projectDescription: String,
    val projectUrl: String,
    val bugReportUrl: String,
    val licenseName: String,
) {
    companion object {
        fun load(pm: PackageManager, packageName: String): AboutMetadata {
            val info = runCatching {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            }.getOrNull()
            return AboutMetadata(
                versionName = info?.versionName ?: "",
                versionCode = info?.longVersionCode ?: 0L,
                developerName = BuildConfig.SKOPOS_DEVELOPER_NAME.trim(),
                developerHandle = BuildConfig.SKOPOS_DEVELOPER_HANDLE.trim(),
                projectDescription = BuildConfig.SKOPOS_PROJECT_DESCRIPTION.trim(),
                projectUrl = BuildConfig.SKOPOS_PROJECT_URL.trim(),
                bugReportUrl = BuildConfig.SKOPOS_BUG_REPORT_URL.trim(),
                licenseName = BuildConfig.SKOPOS_LICENSE_NAME.trim(),
            )
        }
    }
}

/** External-link handling for About rows. URLs come only from build metadata, never input. */
object AboutLinks {

    /**
     * Pure GitHub username derivation, host-testable with dummy URLs.
     *
     * With both inputs: username comes from [projectUrl] when it is a github.com
     * profile/repository URL; otherwise from [handle] when that alone is a valid
     * username. A non-GitHub project URL never fabricates a profile unless the handle
     * provides one. Single-argument form derives from a URL only.
     */
    fun githubUsername(projectUrl: String, handle: String): String? {
        githubUsername(projectUrl)?.let { return it }
        val clean = handle.trim().removePrefix("@")
        if (clean.isEmpty()) return null
        if (!clean.all { it.isLetterOrDigit() || it == '-' }) return null
        return clean
    }

    /**
     * Pure GitHub username derivation from one URL: accepts `github.com/<user>`
     * profile or repository URLs, nothing else. Never appends path segments.
     */
    fun githubUsername(url: String): String? {
        val trimmed = url.trim().trimEnd('/')
        if (!isHttpsUrl(trimmed)) return null
        val hostAndPath = trimmed.substringAfter("://")
        val host = hostAndPath.substringBefore("/")
        if (!host.equals("github.com", ignoreCase = true)) return null
        val user = hostAndPath.substringAfter("/", "").substringBefore("/")
        if (user.isEmpty() || user.contains(" ")) return null
        if (!user.all { it.isLetterOrDigit() || it == '-' }) return null
        return user
    }

    /** Exact developer profile URL for a username: one host, one path segment, nothing else. */
    fun githubProfileUrl(username: String): String? {
        val clean = username.trim()
        if (clean.isEmpty()) return null
        if (!clean.all { it.isLetterOrDigit() || it == '-' }) return null
        return "https://github.com/$clean"
    }

    /**
     * Pure gate, host-testable: only https links may open. Parsed manually (no Uri
     * dependency) so the rule holds identically on JVM tests and device.
     */
    fun isHttpsUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val scheme = trimmed.substringBefore(':')
        if (!scheme.equals("https", ignoreCase = true)) return false
        return trimmed.startsWith("://", startIndex = scheme.length)
    }

    /** Opens [url] externally; false when rejected or no handler exists. */
    fun open(context: Context, url: String): Boolean {
        if (!isHttpsUrl(url)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
