pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.william278.net/releases")
    }
}

rootProject.name = "ArcDuels"

include("domain")
include("storage-mysql")
include("network-redis")
include("paper")

includeBuild("arc-core")
