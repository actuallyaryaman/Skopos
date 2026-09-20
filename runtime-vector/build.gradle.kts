plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.a4real.skopos.runtime"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.a4real.skopos.runtime"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    // Const-only contract; nothing from core is needed at runtime.
    compileOnly(project(":core"))

    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    compileOnly(libs.androidx.annotation)
}