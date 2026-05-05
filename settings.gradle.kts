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
        maven { url = uri("https://s3.amazonaws.com/repo.commonsware.com") }
        // Google AI Edge repository for LiteRT-LM and ML Kit GenAI
        maven { url = uri("https://maven.google.com") }
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
