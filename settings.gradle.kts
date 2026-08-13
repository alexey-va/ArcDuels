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
    }
}

rootProject.name = "RusDuels"

include("domain")
include("storage-mysql")
include("network-redis")
include("paper")

includeBuild("arc-core")
