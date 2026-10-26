// Top-level build file.
// AGP 9.x has built-in Kotlin support, so the old "kotlin-android" plugin is NOT used.
// We only need the Compose compiler plugin, whose version must match the Kotlin version
// that ships with the toolchain (2.4.10 is the version recommended by the Compose docs).
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
