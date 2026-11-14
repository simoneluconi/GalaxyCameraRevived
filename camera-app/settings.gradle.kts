pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GcCamera"
include(":app")
// GC Gallery, and the theme and widgets it shares with the camera (docs/GALLERY-APP.md);
// :transfer is the Wi-Fi transfer library GC Gallery hosts (transfer/README.md)
include(":common", ":gallery", ":transfer")
