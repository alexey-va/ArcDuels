import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("com.gradleup.shadow") version "9.3.0" apply false
}

allprojects {
    group = "ru.ruscrafting.duels"
    version = "0.9.0"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        add("testImplementation", "io.kotest:kotest-runner-junit5:6.0.7")
        add("testImplementation", "io.kotest:kotest-assertions-core:6.0.7")
        add("testImplementation", "io.mockk:mockk:1.14.7")
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // arc-core's MockBukkit bridge instruments Paper classes in the test JVM.
        // Enable direct self-attachment so Byte Buddy does not depend on a
        // separate macOS attach helper, which can block indefinitely on JDK 25.
        jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    }
}

tasks.register("testAll") {
    group = "verification"
    dependsOn(subprojects.map { it.tasks.named("test") })
}
