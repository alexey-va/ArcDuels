plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":storage-mysql"))
    implementation(project(":network-redis"))
    implementation("ru.ruscrafting.arc:arc-core-paper:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.0.0")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.william278.husksync:husksync-bukkit:3.8.7+1.21.8")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.0.0")
    testImplementation("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
}

val pluginVersion = version.toString()

tasks.processResources {
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveBaseName.set("ArcDuels")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
