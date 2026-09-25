/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
plugins {
    `java-library`
}

dependencies {
    // Java has no nullable types; every package is @NullMarked
    api(libs.jspecify)
    implementation(project(":vaadin-uifiber-spi"))
    implementation(libs.slf4j.api)

    // the app brings its own Vaadin
    compileOnly(libs.vaadin.core)
    compileOnly(libs.jakarta.servlet)

    // the API is tested on the real loom runner
    testImplementation(project(":vaadin-uifiber-loom"))
    testImplementation(testFixtures(project(":vaadin-uifiber-loom")))
    testImplementation(libs.vaadin.core)
    testImplementation(libs.karibu.testing)
    testImplementation(libs.junit)
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the loom runner reflects into java.lang.VirtualThread and ThreadBuilders$VirtualThreadBuilder (JDK-8308541)
tasks.withType<Test> {
    jvmArgs(listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"))
}

@Suppress("UNCHECKED_CAST")
val configureMavenCentral = ext["configureMavenCentral"] as (artifactId: String, description: String) -> Unit
configureMavenCentral("vaadin-blocking-dialogs", "Vaadin Blocking Dialogs: the runner-neutral API, a dialog call that blocks until the user answers")
