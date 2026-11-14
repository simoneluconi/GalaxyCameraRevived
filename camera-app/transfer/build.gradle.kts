plugins {
    alias(libs.plugins.android.library)
}

// Wi-Fi transfer: a camera's photos to a browser over HTTPS, with its own hotspot away from any
// Wi-Fi. A library: the host app gives it the photos through PhotoSource (README.md).
// Framework only, no AndroidX.
android {
    namespace = "com.simoneluconi.gctransfer"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The colour tokens, the panel and the text button the transfer screen is drawn with
    implementation(project(":common"))
    testImplementation(libs.junit)
}
