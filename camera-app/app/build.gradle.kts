plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.simoneluconi.gccamera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simoneluconi.gccamera"
        // Built for one device: the EK-GC100 on LineageOS 21 (Android 14)
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "armeabi-v7a" }
    }

    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
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
    // Only the settings screen uses AndroidX; the viewfinder runs on the plain framework (RAM)
    implementation(libs.appcompat)
    implementation(libs.preference)
    implementation(libs.material)
    // Lens, ISO and GPS tags the framework ExifInterface lacks; used only on the writer thread
    implementation(libs.exifinterface)
}
