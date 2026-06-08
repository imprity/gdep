import com.diffplug.spotless.LineEnding

plugins {
    application

    id("com.diffplug.spotless").version("8.6.0")
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()

    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
    implementation("org.gradle:gradle-tooling-api:9.2.1")
    // The tooling API need an SLF4J implementation available at runtime
    // We just use slf4j-simple cause it does everything I need
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    implementation("com.dslplatform:dsl-json:2.0.2")
    annotationProcessor("com.dslplatform:dsl-json:2.0.2")

    testImplementation(libs.junit)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// formatter setting
spotless {
     java {
         lineEndings= LineEnding.UNIX
         removeUnusedImports()
         palantirJavaFormat()
         toggleOffOn()
     }
}

// java compile settings
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// build fat jar
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.gdep.App"
    }

    from({
        configurations.runtimeClasspath.get().map { 
            if (it.isDirectory()) it else zipTree(it) 
        }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

application {
    // Define the main class for the application.
    mainClass = "com.gdep.App"
}
