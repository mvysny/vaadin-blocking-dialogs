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
    // Java has no nullable types
    api(libs.jetbrains.annotations)

    // the app brings its own Vaadin
    compileOnly(libs.vaadin.core)

    testImplementation(libs.vaadin.core)
    testImplementation(libs.karibu.testing)
    testImplementation(libs.junit)
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

@Suppress("UNCHECKED_CAST")
val configureMavenCentral = ext["configureMavenCentral"] as (artifactId: String) -> Unit
configureMavenCentral("vaadin-blocking-dialogs")
