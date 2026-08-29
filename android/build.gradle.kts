// Versions deliberately pinned to the same set WeightTracker builds with, so the
// two apps can share a Firebase project and a toolchain without drifting apart.
// androidx.health.connect:connect-client 1.1.0 needs AGP 8.9.1+ and compileSdk 36.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
