plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation("ru.ruscrafting.arc:arc-core:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    testImplementation("ru.ruscrafting.arc:arc-core-testing:2.0.0")
}
