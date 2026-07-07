pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "NosmaiDetectionDemo"
include(":app")

// Using the PREBUILT AAR (app/libs/nosmai-detection.aar) — no native build here,
// so it installs fast and offline. To develop the SDK from source instead (edit
// Kotlin/C++ and Run), uncomment the two lines below and swap the app dependency
// back to implementation(project(":sdk")).
// include(":sdk")
// project(":sdk").projectDir = file("../../nosmai-sdk/platforms/android/sdk")
