import java.security.MessageDigest

plugins {
    id("io.github.drownek.plugwright") version "2.0.4"
    kotlin("jvm")
    id("com.gradleup.shadow")
}

val staffSpellApiJar = providers.gradleProperty("staffSpellApiJar").orNull?.let(::file)
if (staffSpellApiJar != null) require(staffSpellApiJar.isFile) { "Staff spell API JAR does not exist: $staffSpellApiJar" }
val staffSpellApiDependency: Any = staffSpellApiJar?.let { files(it) }
    ?: "ru.ruscrafting.arc:arc-staff-spells-api:0.1.1"
val dungeonsApiJar = providers.gradleProperty("dungeonsApiJar").orNull?.let(::file)
if (dungeonsApiJar != null) require(dungeonsApiJar.isFile) { "Dungeons API JAR does not exist: $dungeonsApiJar" }
val dungeonsApiDependency: Any = dungeonsApiJar?.let { files(it) }
    ?: "ru.ruscrafting.arc:arc-dungeons-api:0.1.1"
val e2eArcJar = providers.gradleProperty("e2eArcJar").map { project.file(it) }
val e2eArcCoreJar = providers.gradleProperty("e2eArcCoreJar").map { project.file(it) }
val verifyE2eProvider = tasks.register("verifyE2eProvider") {
    description = "Verifies the standalone provider used by the isolated Paper fixture."
    inputs.file(e2eArcCoreJar).optional()
    doLast {
        val provider = requireNotNull(e2eArcCoreJar.orNull) {
            "Supply -Pe2eArcCoreJar=/absolute/path/ArcCore-0.1.12.jar for the real Paper fixture"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(provider.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == "93158072f30ad3d7d5201acdf9036324841c4455844b4f1b650cfea0d8540502") {
            "ArcCore 0.1.12 fixture checksum mismatch: $digest"
        }
    }
}
tasks.withType<me.drownek.plugwright.AbstractPlugwrightTask>().configureEach {
    dependsOn(verifyE2eProvider)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":storage-mysql"))
    implementation(project(":network-redis"))
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.41")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.41")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.41")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.41")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.41")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.41")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.41")
    compileOnly(staffSpellApiDependency)
    compileOnly(dungeonsApiDependency)
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.william278.husksync:husksync-bukkit:3.8.7+1.21.8")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.41")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.41")
    testImplementation(staffSpellApiDependency)
    testImplementation(dungeonsApiDependency)
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
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
    exclude("ru/arc/paper/api/**")
    exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
    exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
    exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("ru/arc/staffspells/api/**")
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
    downloadPlugins {
        url("https://github.com/retrooper/packetevents/releases/download/v2.12.1/packetevents-spigot-2.12.1.jar")
    }
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    writeFiles {
        e2eArcJar.orNull?.let { file("plugins/ARC.jar", it) }
        e2eArcCoreJar.orNull?.let { file("plugins/ArcCore.jar", it) }
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/ARC/modules/runtime.yml", projectDir.resolve("src/test/e2e/fixtures/arc-runtime.yml"))
        file("plugins/ARC/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/arc-redis.yml"))
        file("plugins/ARC/modules/ops-http.yml", projectDir.resolve("src/test/e2e/fixtures/arc-ops-http.yml"))
        file("plugins/ArcDuels/config.yml", projectDir.resolve("src/test/e2e/fixtures/config.yml"))
    }
}
