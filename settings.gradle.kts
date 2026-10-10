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
    }
}

rootProject.name = "DVScreator"

include(":app")
include(":core:model")
include(":core:imaging")
include(":core:webp")
include(":core:data")
include(":core:whatsapp")
include(":feature:packs")
include(":feature:editor")
include(":feature:export")
