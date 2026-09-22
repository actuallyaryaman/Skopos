plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.a4real.skopos"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.a4real.skopos"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.1"
    }

    // Release signing is intentionally unconfigured: the release APK is signed by the
    // release owner with an external key after a keystore is created separately. No
    // signing credentials, property files, or debug-signing fallbacks belong here.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Private About-screen metadata (`.local/skopos-about.txt`, never committed).
    // Missing file or fields degrade to empty strings; the UI hides blank rows.
    val aboutMetadata: Map<String, String> = run {
        val file = rootProject.file(".local/skopos-about.txt")
        if (!file.isFile) return@run emptyMap()
        file.readLines()
            .mapNotNull { line ->
                val key = line.substringBefore("=").trim()
                if (key.isEmpty() || !line.contains("=")) null
                else key to line.substringAfter("=").trim()
            }.toMap()
    }
    fun about(key: String): String = aboutMetadata[key].orEmpty().replace("\"", "")
    defaultConfig {
        buildConfigField("String", "SKOPOS_DEVELOPER_NAME", "\"${about("DEVELOPER_NAME")}\"")
        buildConfigField("String", "SKOPOS_DEVELOPER_HANDLE", "\"${about("DEVELOPER_HANDLE")}\"")
        buildConfigField("String", "SKOPOS_PROJECT_DESCRIPTION", "\"${about("PROJECT_DESCRIPTION")}\"")
        buildConfigField("String", "SKOPOS_PROJECT_URL", "\"${about("PROJECT_URL")}\"")
        buildConfigField("String", "SKOPOS_BUG_REPORT_URL", "\"${about("BUG_REPORT_URL")}\"")
        buildConfigField("String", "SKOPOS_LICENSE_NAME", "\"${about("LICENSE_NAME")}\"")
    }
}

// GitHub avatar packaging: ignored local `.local/github-avatar.png` wins; otherwise a
// generic checked-in fallback is used so the build never depends on network or files
// outside the repo. The generated asset (not the ignored source) ships in the APK.
val generatedAboutAssets = layout.buildDirectory.dir("generated/aboutAssets")
val prepareGithubAvatar by tasks.registering(Copy::class) {
    val localAvatar = rootProject.file(".local/github-avatar.png")
    from(if (localAvatar.isFile) localAvatar else rootProject.file("app/about-assets/fallback-avatar.png"))
    rename { "github_avatar.png" }
    into(generatedAboutAssets)
}
android {
    sourceSets {
        named("main") {
            // Plain path (not a Provider): the Android SourceSet API rejects Providers.
            assets.srcDir("build/generated/aboutAssets")
        }
    }
}
tasks.matching {
    (it.name.startsWith("merge") &&
        (it.name.contains("Assets") || it.name.contains("Resources"))) ||
        it.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(prepareGithubAvatar)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime-vector"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // The manager side of the Vector IPC: XposedProvider (declared in the manifest below)
    // receives the daemon's binder; this packages the service bridge into the APK. The API
    // classes themselves are supplied by the Vector framework in the injected process, so the
    // runtime module only ever compiles against them.
    implementation(libs.libxposed.service)
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)

    testImplementation("junit:junit:4.13.2")
}