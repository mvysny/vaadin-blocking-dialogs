/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

defaultTasks("clean", "build")

// Checks the doc layer described in AGENTS.md, "Design docs" - and nothing about the code.
// Wired into `check` so that a plain `./gradlew` runs it; needs bash+git, so it is skipped on Windows.
val verifyDesignTripwires = tasks.register<Exec>("verifyDesignTripwires") {
    description = "Verifies AGENTS.md and design/ against the design-docs rules."
    group = "verification"
    commandLine("./design/verify_design_tripwires.sh")
    onlyIf { !System.getProperty("os.name").startsWith("Windows") }
}
tasks.named("check") { dependsOn(verifyDesignTripwires) }

allprojects {
    group = "com.github.mvysny.vaadin-blocking-dialogs"
    version = "0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {

    apply {
        plugin("maven-publish")
        plugin("java")
        plugin("org.gradle.signing")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            // to see the exceptions of failed tests in CI console.
            exceptionFormat = TestExceptionFormat.FULL
            showCauses = true
        }
        // the loom runner's opt-in to Java 21-23; CI's JDK 21 job passes -D to gradlew
        System.getProperty("blockingdialogs.uifiber.loom.allowPinningJdk")?.let {
            systemProperty("blockingdialogs.uifiber.loom.allowPinningJdk", it)
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // creates a reusable function which configures proper deployment to Maven Central
    ext["configureMavenCentral"] = { artifactId: String, description: String ->

        java {
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<Javadoc> {
            isFailOnError = false
            // the JDK's own tags, which javadoc doesn't know without being told
            (options as StandardJavadocDocletOptions).tags(
                "apiNote:a:API Note:", "implSpec:a:Implementation Requirements:", "implNote:a:Implementation Note:")
        }

        tasks.withType<JavaCompile> {
            options.isDeprecation = true
        }

        publishing {
            publications {
                create("mavenJava", MavenPublication::class.java).apply {
                    groupId = project.group.toString()
                    this.artifactId = artifactId
                    version = project.version.toString()
                    pom {
                        this.description = description
                        name = artifactId
                        url = "https://github.com/mvysny/vaadin-blocking-dialogs"
                        licenses {
                            license {
                                name = "The Apache License, Version 2.0"
                                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                                distribution = "repo"
                            }
                        }
                        developers {
                            developer {
                                id = "mavi"
                                name = "Martin Vysny"
                                email = "martin@vysny.me"
                            }
                        }
                        scm {
                            url = "https://github.com/mvysny/vaadin-blocking-dialogs"
                        }
                    }

                    from(components["java"])
                }
            }
        }

        signing {
            sign(publishing.publications["mavenJava"])
        }
    }
}

nexusPublishing {
    repositories {
        // see https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
