package org.a4real.skopos.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Process-wide bounded cache of downscaled launcher icons, keyed by package name. Rows are
 * rebuilt on every poll, so icons must never be reloaded per recomposition or per keystroke:
 * all loads happen here, on background threads, exactly once per package until evicted.
 * ~96 px bitmaps (~36 KB each); LRU-capped well below any memory concern for an app list.
 * Null means unavailable — the UI renders an initial-letter placeholder instead.
 */
object AppIconCache {
    private const val MAX_ENTRIES = 150
    private const val SIZE_PX = 96

    private val cache =
        object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap?>,
            ): Boolean = size > MAX_ENTRIES
        }

    @Synchronized
    fun get(context: Context, packageName: String): ImageBitmap? {
        cache[packageName]?.let { return it }
        // LinkedHashMap.get returns null both for absent and null-mapped keys; containsKey
        // distinguishes so a failed load is itself cached and never retried per poll.
        if (cache.containsKey(packageName)) return null
        val bitmap = runCatching {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(SIZE_PX, SIZE_PX)
                .asImageBitmap()
        }.getOrNull()
        cache[packageName] = bitmap
        return bitmap
    }

    @Synchronized
    fun peek(packageName: String): ImageBitmap? =
        if (cache.containsKey(packageName)) cache[packageName] else null
}
