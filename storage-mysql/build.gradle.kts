plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation("ru.arc:arc-core-sql:1.0-SNAPSHOT")
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers-mysql:2.0.5")
}

tasks.register<Test>("integrationTest") {
    description = "Runs MySQL integration tests against a disposable Docker container"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
