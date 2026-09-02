plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation("ru.ruscrafting.arc:arc-core:2.4.3")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.4.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    testImplementation("ru.ruscrafting.arc:arc-core-testing:2.4.3")
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
    description = "Runs Redis integration tests against a disposable Docker container"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
