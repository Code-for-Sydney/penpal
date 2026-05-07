# Module Setup — Gradle multi-module

## Directory layout

**Note:** `build-logic/convention` does not exist. Each module has its own `build.gradle.kts`.

```
penpal/
├── app/
│   └── src/main/
├── feature/
│   ├── chat/
│   ├── notebooks/
│   ├── process/
│   ├── inference/
│   └── settings/
├── core/
│   ├── ai/
│   ├── data/
│   ├── media/       # Empty shell (no source files)
│   ├── processing/
│   └── ui/
├── settings.gradle.kts
└── build.gradle.kts
```

---

## settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Penpal"

include(":app")
include(":core:ai")
include(":core:data")
include(":core:media")
include(":core:processing")
include(":core:ui")
include(":feature:chat")
include(":feature:process")
include(":feature:inference")
include(":feature:notebooks")
include(":feature:settings")
```

---

## Convention plugins

**Note:** `build-logic/convention` does not exist in the project. Each module has its own full `build.gradle.kts`.

For a future refactoring, convention plugins could be extracted to `build-logic` to avoid repeating the same configuration in every module:

```kotlin
// build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt (PLANNED)
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
            apply("org.jetbrains.kotlin.plugin.compose")
        }
        extensions.configure<LibraryExtension> {
            compileSdk = 34
            defaultConfig { minSdk = 26 }
            buildFeatures { compose = true }
        }
        dependencies {
            add("api", project(":core:ui"))
            add("api", project(":core:data"))
        }
    }
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
agp = "9.1.1"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
hilt = "2.51.1"
room = "2.6.1"
workManager = "2.9.1"
coroutines = "1.8.1"
composeBom = "2024.06.00"
onnxruntime = "1.19.0"

[libraries]
androidx-compose-bom     = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-room-runtime    = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx        = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-work-runtime    = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
litertlm-android         = { group = "com.google.ai.edge.litertlm", name = "litertlm-android", version.ref = "litertlm" }
onnxruntime-android      = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }

[plugins]
android-application      = { id = "com.android.application", version.ref = "agp" }
android-library          = { id = "com.android.library", version.ref = "agp" }
kotlin-android           = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
ksp                      = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```
