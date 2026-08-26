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
        maven("https://repo.rus-crafting.ru/grocermc/") {
            content { includeGroup("ru.ruscrafting.arc") }
        }
    }
}

rootProject.name = "ArcDuels"

include("domain")
include("storage-mysql")
include("network-redis")
include("paper")

providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { arcCoreDir ->
    require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
        "arcCoreDir must point to an arc-core checkout"
    }
    includeBuild(arcCoreDir) {
        dependencySubstitution {
            listOf(
                "arc-core",
                "arc-core-integration-testing",
                "arc-core-logging",
                "arc-core-paper",
                "arc-core-paper-testing",
                "arc-core-redis",
                "arc-core-sql",
                "arc-core-testing",
            ).forEach { artifact ->
                substitute(module("ru.ruscrafting.arc:$artifact")).using(project(":$artifact"))
            }
        }
    }
}
