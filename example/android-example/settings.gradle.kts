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
rootProject.name = "notification-android-example"
include(":app")
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.notification:android-sdk")).using(project(":android"))
    }
}
