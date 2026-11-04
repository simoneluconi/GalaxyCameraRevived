plugins {
    alias(libs.plugins.android.library)
}

// Theme tokens and Canvas helpers shared by GC Camera and GC Gallery. Framework only: no AndroidX
android {
    namespace = "com.simoneluconi.gccommon"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
