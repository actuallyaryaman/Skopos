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
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }
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

    // The manager side of the Vector IPC: XposedProvider (declared in the manifest below)
    // receives the daemon's binder; this packages the service bridge into the APK. The API
    // classes themselves are supplied by the Vector framework in the injected process, so the
    // runtime module only ever compiles against them.
    implementation(libs.libxposed.service)
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)

    testImplementation("junit:junit:4.13.2")
}