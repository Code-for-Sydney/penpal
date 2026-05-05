plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.penpal.feature.notebooks"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core:ai"))
    api(project(":core:data"))
    api(project(":core:ui"))

    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")

    // For WebView-based LaTeX rendering
    api("androidx.webkit:webkit:1.10.0")

    // For image loading in ImageBlock
    api("io.coil-kt:coil-compose:2.5.0")

    // For JSON serialization of blocks
    api("com.google.code.gson:gson:2.11.0")

    debugApi("androidx.compose.ui:ui-tooling")
}