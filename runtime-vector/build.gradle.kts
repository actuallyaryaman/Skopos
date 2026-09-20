plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.a4real.skopos.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))

    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    compileOnly(libs.androidx.annotation)
}