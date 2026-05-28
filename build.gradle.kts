plugins {
    application
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()

    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
    implementation("org.gradle:gradle-tooling-api:9.2.1")
    // The tooling API need an SLF4J implementation available at runtime, replace this with any other implementation
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // Use JUnit test framework.
    testImplementation(libs.junit)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // Define the main class for the application.
    mainClass = "com.gdep.App"
}
