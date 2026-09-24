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
    api(project(":vaadin-blocking-dialogs"))
    implementation(libs.slf4j.api)

    // the app brings its own Vaadin
    compileOnly(libs.vaadin.core)

    // MockVirtualThreadAwareServlet, shared with the testapp's tests
    testFixturesApi(libs.karibu.testing)
    testFixturesCompileOnly(libs.vaadin.core)

    testImplementation(libs.vaadin.core)
    testImplementation(libs.junit)
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// LoomUtils reflects into java.lang.ThreadBuilders$VirtualThreadBuilder (JDK-8308541)
tasks.withType<Test> {
    jvmArgs(listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"))
}

@Suppress("UNCHECKED_CAST")
val configureMavenCentral = ext["configureMavenCentral"] as (artifactId: String) -> Unit
configureMavenCentral("vaadin-blocking-dialogs-loom")

// the test fixtures are for this repo's own tests; don't publish them to Maven Central
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
