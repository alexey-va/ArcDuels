plugins {
    id("io.github.drownek.plugwright") version "2.0.4"
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":storage-mysql"))
    implementation(project(":network-redis"))
    implementation("ru.ruscrafting.arc:arc-core-paper:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.5.0")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.6")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.william278.husksync:husksync-bukkit:3.8.7+1.21.8")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.5.0")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.6")
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

// Isolated real-Paper tests run separately from the fast JVM suite.
plugwright {
    minecraftVersion.set("1.21.11")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/ArcDuels/config.yml", projectDir.resolve("src/test/e2e/fixtures/config.yml"))
    }
}
