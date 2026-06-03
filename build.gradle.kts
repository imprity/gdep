import com.diffplug.spotless.LineEnding
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    application

    id("com.diffplug.spotless").version("8.6.0")
    id("net.ltgt.errorprone").version("5.1.0")
    id("net.ltgt.nullaway").version("3.0.0")
    id("com.github.spotbugs").version("6.5.5")
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

    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jspecify:jspecify:1.0.0")

    // Use JUnit test framework.
    testImplementation(libs.junit)

    errorprone("com.google.errorprone:error_prone_core:2.42.0") 
    errorprone("com.uber.nullaway:nullaway:0.13.4")

}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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

nullaway {
    onlyNullMarked = true
    jspecifyMode = true
}

// java compile settings
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-XDaddTypeAnnotationsToSymbol=true")

    options.errorprone  {
		error("RequireExplicitNullMarking") 
        error("NullAway")
		nullaway {
			error()
		}
	}

    options.errorprone.disableWarningsInGeneratedCode = true
}

spotbugs {
    ignoreFailures = false
    effort = com.github.spotbugs.snom.Effort.DEFAULT
    reportLevel = com.github.spotbugs.snom.Confidence.DEFAULT
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
