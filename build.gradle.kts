// Declared once here so :app and :parser share one Kotlin Gradle plugin classloader.
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.google.devtools.ksp") apply false
}
