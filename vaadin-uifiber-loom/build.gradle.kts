/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":vaadin-uifiber-spi"))
    implementation(libs.slf4j.api)

    // the app brings its own Vaadin
    compileOnly(libs.vaadin.core)
    compileOnly(libs.jakarta.servlet)

    // MockVirtualThreadAwareServlet, shared with the testapp's tests
    testFixturesApi(libs.karibu.testing)
    testFixturesCompileOnly(libs.vaadin.core)

    // its tests drive the runner through the API, as an app does
    testImplementation(project(":vaadin-blocking-dialogs"))
    testImplementation(libs.vaadin.core)
    testImplementation(libs.junit)
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// LoomUtils reflects into java.lang.VirtualThread and ThreadBuilders$VirtualThreadBuilder (JDK-8308541)
tasks.withType<Test> {
    jvmArgs(listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"))
}

@Suppress("UNCHECKED_CAST")
val configureMavenCentral = ext["configureMavenCentral"] as (artifactId: String, description: String) -> Unit
configureMavenCentral("vaadin-uifiber-loom", "Vaadin Blocking Dialogs: the virtual-thread UI fiber runner")

// the test fixtures are for this repo's own tests; don't publish them to Maven Central
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
