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

    testOptions {
        // Policy logic must stay free of Android framework mocks on the host — unit tests run
        // against the stripped android.jar stubs, and the seams below keep real android classes
        // entirely out of the executable path by default.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    compileOnly(libs.androidx.annotation)

    testImplementation("junit:junit:4.13.2")
}