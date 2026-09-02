plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation("ru.ruscrafting.arc:arc-core-sql:2.4.3")
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, "ru.ruscrafting.arc:arc-core-integration-testing:2.4.3")
}

tasks.register<Test>("integrationTest") {
    description = "Runs MySQL integration tests against a disposable Docker container"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
