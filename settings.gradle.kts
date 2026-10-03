pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.4.1"
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
        id("com.google.devtools.ksp") version "2.3.12"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "alpha"
include(":parser", ":app")
