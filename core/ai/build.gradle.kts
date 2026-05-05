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
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("com.google.code.gson:gson:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // Note: LiteRT-LM and ML Kit GenAI are not yet publicly available
    // They require special access from Google. When available, add:
    // implementation("com.google.ai.edge.litertlm:litertlm-android:0.1.0")
    // implementation("com.google.mlkit:genai.prompt:1.0.0-beta1")

    implementation(project(":core:data"))
}