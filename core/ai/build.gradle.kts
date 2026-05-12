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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // WorkManager for background model downloads
    implementation(libs.androidx.work.runtime.ktx)

    // LiteRT-LM for on-device LLM inference
    implementation(libs.litertlm.android)

    // ONNX Runtime for embeddings
    implementation(libs.onnxruntime.android)

    implementation(project(":core:data"))
}