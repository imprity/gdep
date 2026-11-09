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

val MIMA_VER = "3.0.1"
val MAVEN_RESOLVER_VER = "2.0.24"
val MAVEN_VER = "3.10.0"

dependencies {
    implementation("org.gradle:gradle-tooling-api:9.2.1")

    // MIMA stuff
    implementation("eu.maveniverse.maven.mima:context:" + MIMA_VER)
    implementation("eu.maveniverse.maven.mima.extensions:mmr:" + MIMA_VER)
    runtimeOnly("eu.maveniverse.maven.mima.runtime:standalone-static:" + MIMA_VER)

    // Maven stuff
    implementation("org.apache.maven.resolver:maven-resolver-api:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-spi:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-util:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-named-locks:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-impl:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-transport-classpath:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-transport-file:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-transport-apache:" + MAVEN_RESOLVER_VER)
    implementation("org.apache.maven.resolver:maven-resolver-transport-url:" + MAVEN_RESOLVER_VER)

    // More Maven stuff
    implementation("org.apache.maven:maven-plugin-api:" + MAVEN_VER)
    implementation("org.apache.maven:maven-core:" + MAVEN_VER)
    implementation("org.apache.maven:maven-settings-builder:" + MAVEN_VER)
    implementation("org.apache.maven:maven-model:" + MAVEN_VER)
    implementation("org.apache.maven:maven-model-builder:" + MAVEN_VER)
    implementation("org.apache.maven:maven-builder-support:" + MAVEN_VER)
    implementation("org.apache.maven:maven-resolver-provider:" + MAVEN_VER)
    implementation("org.apache.maven:maven-plugin-api:" + MAVEN_VER)

    // The tooling API need an SLF4J implementation available at runtime
    // We just use slf4j-simple cause it does everything I need
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    implementation("com.dslplatform:dsl-json:2.0.2")
    annotationProcessor("com.dslplatform:dsl-json:2.0.2")

    testImplementation(libs.junit)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
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
    options.release = 8
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
