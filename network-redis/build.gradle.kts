plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
