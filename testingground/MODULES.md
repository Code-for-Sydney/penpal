# Module Setup — Gradle multi-module

## Directory layout

```
penpal/
├── app/
│   └── src/main/
├── feature/
│   ├── chat/
│   ├── notebooks/
│   ├── process/
│   ├── organize/
│   └── settings/
├── core/
│   ├── ai/
│   ├── data/
│   ├── media/
│   ├── processing/
│   └── ui/
├── build-logic/
│   └── convention/          # shared Gradle conventions
├── settings.gradle.kts
└── build.gradle.kts
```

---

## settings.gradle.kts

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google(); mavenCentral(); gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "penpal"

include(":app")
include(":feature:chat", ":feature:notebooks", ":feature:process",
        ":feature:organize", ":feature:settings")
include(":core:ai", ":core:data", ":core:media", ":core:processing", ":core:ui")
```

---

## Convention plugins — build-logic/convention

Avoid repeating the same 80-line `build.gradle.kts` in every module. Extract to convention plugins.

```kotlin
// build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
            apply("com.google.devtools.ksp")
            apply("com.google.dagger.hilt.android")
        }
        extensions.configure<LibraryExtension> {
            compileSdk = 35
            defaultConfig { minSdk = 26 }
            buildFeatures { compose = true }
        }
        dependencies {
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:data"))
            add("implementation", libs.findLibrary("hilt.android").get())
            add("ksp", libs.findLibrary("hilt.compiler").get())
        }
    }
}
```

Feature modules then become concise:

```kotlin
// feature/chat/build.gradle.kts
plugins { id("penpal.android.feature") }

android { namespace = "ai.penpal.feature.chat" }

dependencies {
    implementation(project(":core:ai"))   // only feature-specific extras
}
```

---

## Core module dependency matrix

| Module | Depends on |
|---|---|
| `:core:ui` | — |
| `:core:data` | — |
| `:core:media` | `:core:data` |
| `:core:ai` | `:core:data` |
| `:core:processing` | `:core:ai` · `:core:media` · `:core:data` |
| `:feature:*` | `:core:ui` · `:core:data` |
| `:feature:chat` | + `:core:ai` |
| `:feature:process` | + `:core:processing` · `:core:media` |
| `:feature:organize` | + `:core:ai` (graph layout) |
| `:app` | all features |

---

## libs.versions.toml (key entries)

```toml
[versions]
compileSdk = "35"
minSdk = "26"
kotlin = "2.0.21"
agp = "8.7.3"
compose-bom = "2024.12.01"
hilt = "2.52"
room = "2.7.0"
work = "2.10.0"
datastore = "1.1.1"
onnxruntime = "1.20.0"
retrofit = "2.11.0"
coil = "2.7.0"

[libraries]
compose-bom              = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui               = { group = "androidx.compose.ui", name = "ui" }
compose-material3        = { group = "androidx.compose.material3", name = "material3" }
hilt-android             = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler            = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-work                = { group = "androidx.hilt", name = "hilt-work", version = "1.2.0" }
room-runtime             = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx                 = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler            = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
work-runtime-ktx         = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
datastore-proto          = { group = "androidx.datastore", name = "datastore", version.ref = "datastore" }
onnxruntime-android      = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
retrofit-core            = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
coil-compose             = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }

[plugins]
android-application      = { id = "com.android.application", version.ref = "agp" }
android-library          = { id = "com.android.library", version.ref = "agp" }
kotlin-android           = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
hilt                     = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp                      = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.25" }
```
