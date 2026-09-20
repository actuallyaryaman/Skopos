plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Pinning the Kotlin plugins here sets the version on the buildscript classpath
    // for every module. AGP 9 supplies Kotlin itself; a module must not apply the
    // kotlin-android plugin, only the Compose plugin below.
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose) apply false
}