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

rootProject.name = "omnitak-android"
include(":app")
// Plugin SDK (public interfaces + host contract) and the bundled reference
// plugins. Authors add a plugin by dropping a module under plugins/ and adding
// one include() line here + one implementation(project(...)) in app/build.gradle.kts.
include(":plugins:plugin-sdk")
include(":plugins:example-adsb")
