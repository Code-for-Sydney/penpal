plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.penpal.core.ai"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("com.google.code.gson:gson:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api(libs.jsoup)

    // WorkManager for background model downloads
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // LiteRT-LM for on-device LLM inference
    implementation(libs.litertlm.android)

    // ONNX Runtime for embeddings
    implementation(libs.onnxruntime.android)

    implementation(project(":core:data"))
}