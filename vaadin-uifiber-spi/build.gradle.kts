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

    // the app brings its own Vaadin
    compileOnly(libs.vaadin.core)
}

@Suppress("UNCHECKED_CAST")
val configureMavenCentral = ext["configureMavenCentral"] as (artifactId: String, description: String) -> Unit
configureMavenCentral("vaadin-uifiber-spi", "Vaadin Blocking Dialogs: the SPI a UI fiber runner implements")
