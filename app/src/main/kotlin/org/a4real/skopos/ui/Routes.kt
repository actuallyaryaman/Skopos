package org.a4real.skopos.ui

import org.a4real.skopos.data.AppRow

/** Explicit manager navigation routes; detail state resets per package via key(). */
internal sealed interface Route {
    data object Home : Route
    data object ContactsApps : Route
    data class Detail(val entry: AppRow) : Route
    data object Settings : Route
    data object AdvancedSettings : Route

    /** System-back target, or null to leave the activity. */
    fun back(): Route? = when (this) {
        is Home -> null
        is ContactsApps -> Home
        is Detail -> ContactsApps
        is Settings -> Home
        is AdvancedSettings -> Settings
    }

    /** Bottom-bar tab shown for this route, or null on drill-down screens. */
    fun bottomTab(): BottomTab? = when (this) {
        is Home -> BottomTab.HOME
        is Settings -> BottomTab.SETTINGS
        else -> null
    }
}

/** Top-level bottom-navigation destinations. */
internal enum class BottomTab { HOME, SETTINGS }

/** Page-transition classification; explicit so sealed-class order never implies direction. */
internal enum class TransitionKind { TOP_LEVEL, FORWARD, BACKWARD }

private fun Route.rank(): Int = when (this) {
    is Route.Home, is Route.Settings -> 0
    is Route.ContactsApps, is Route.AdvancedSettings -> 1
    is Route.Detail -> 2
}

/**
 * Classifies a route change. Home<->Settings is a lateral tab switch (fade only);
 * anything else follows hierarchy depth. Same-rank, same-screen pairs fall back to fade.
 */
internal fun transitionKind(from: Route, to: Route): TransitionKind {
    val homeSettingsPair =
        (from is Route.Home && to is Route.Settings) ||
            (from is Route.Settings && to is Route.Home)
    if (homeSettingsPair) return TransitionKind.TOP_LEVEL
    val delta = to.rank() - from.rank()
    return when {
        delta > 0 -> TransitionKind.FORWARD
        delta < 0 -> TransitionKind.BACKWARD
        else -> TransitionKind.TOP_LEVEL
    }
}
