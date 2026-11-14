plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.simoneluconi.gcgallery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simoneluconi.gcgallery"
        // Built for one device: the EK-GC100 on LineageOS 21 (Android 14)
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded on the user's own camera, so the debug key is enough for now
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))
    // The Wi-Fi transfer; the gallery hosts it (transfer/GalleryPhotoSource)
    implementation(project(":transfer"))
    // EXIF, XMP and thumbnails from JPEG and DNG; the framework ExifInterface lacks the lens and GPS accuracy tags
    implementation(libs.exifinterface)
    // JVM tests of the byte-level parts (XMP patching, orientation maths)
    testImplementation(libs.junit)
}
