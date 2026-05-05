plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.penpal.core.ui"
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
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.activity:activity-compose:1.9.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    api(platform("androidx.compose:compose-bom:2024.06.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-core")
    api("androidx.compose.material:material-icons-extended")
    debugApi("androidx.compose.ui:ui-tooling")
}