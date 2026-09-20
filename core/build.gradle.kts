plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.a4real.skopos.core"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}