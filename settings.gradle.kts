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
        maven("https://maven.enginehub.org/repo/")
    }
}

rootProject.name = "ArcDuels"

include("domain")
include("storage-mysql")
include("network-redis")
include("paper")

val arcCoreDir = providers.gradleProperty("arcCoreDir").orNull?.let(::file) ?: file("arc-core")
require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
    "arcCoreDir must point to an arc-core checkout"
}
includeBuild(arcCoreDir)
