plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase Analytics turns on only when a Firebase config is present (it is not committed).
// Without it the app builds and runs the same, and Analytics.event() does nothing.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "app.hail.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hail.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Emulator alias for the host machine. Change at runtime on the Debug screen.
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://10.0.2.2:8000\"")
    }

    signingConfigs {
        getByName("debug") {
            // Committed debug-only key so every CI build installs over the previous one.
            // Never use it for a release build.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["cleartext"] = "false"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("hail.screens", (project.findProperty("hailScreens") ?: "").toString())
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Bundle the reader config (single source: backend/app/readers_config.json) so the app can read
// fares even before it has reached the backend, e.g. the Debug "Test fare check".
val copyReaderConfig by tasks.registering(Copy::class) {
    from(rootProject.file("../backend/app/readers_config.json"))
    into(layout.buildDirectory.dir("generated/hailAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/hailAssets"))
tasks.named("preBuild") { dependsOn(copyReaderConfig) }

dependencies {
    // Jetpack Compose (Google, first-party) for the app UI. See docs/decisions.md.
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Firebase Analytics (Google). Coarse events only; see Analytics.kt and docs/decisions.md.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    // Test-only. The app itself has no third-party dependencies.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // UI screenshots on the JVM (real Android views, no device): ./gradlew screenshots
    testImplementation("org.robolectric:robolectric:4.17")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Renders the real app screens to app/build/ui-screens/*.png (see UiScreenshots.kt).
tasks.register("screenshots") {
    group = "verification"
    description = "Render the app's screens to PNG with Robolectric (no device needed)."
    dependsOn("testDebugUnitTest")
}
