package org.a4real.skopos.ui

/** Explicit manager navigation routes; detail state resets per package via key(). */
internal sealed interface Route {
    data object Home : Route
    data object ContactsApps : Route
    data class Detail(val packageName: String) : Route
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
