plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x supplies the Compose compiler as a plugin; there is no
    // composeOptions { kotlinCompilerExtensionVersion } block any more.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Applied only when the file is actually there.
//
// The google-services plugin fails the build at configuration time if
// google-services.json is missing, which would mean a fresh checkout couldn't
// even be run until someone had been to the Firebase console. Making it
// conditional lets the app build and launch straight away — AccountManager
// already detects the missing config at runtime and the sign-in screen says so,
// rather than the whole project refusing to configure.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.avi.journal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avi.journal"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Same BOM as WeightTracker: the release right after Kotlin 2.3, so the
    // Compose runtime and the 2.3 Compose compiler match.
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // No material-icons dependency, matching WeightTracker — the icons this app
    // needs are drawn in ui/components/Icons.kt.

    implementation("androidx.health.connect:connect-client:1.1.0")

    // Location is NOT part of Health Connect: it has no general location type.
    // Coarse location comes from Play services' fused provider, requested only
    // when the writer taps "use my location".
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Kotlin 2.3 made the old `android { kotlinOptions { jvmTarget } }` a hard error.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
