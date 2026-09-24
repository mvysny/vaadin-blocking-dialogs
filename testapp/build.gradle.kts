/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
plugins {
    alias(libs.plugins.vaadin)
    application
}

dependencies {
    implementation(project(":vaadin-blocking-dialogs-loom"))

    // Vaadin
    implementation(libs.vaadin.core)
    if (!vaadin.effective.productionMode.get()) {
        implementation(libs.vaadin.dev)
    }
    implementation(libs.vaadin.boot)

    // logging: SLF4J API to SLF4J-Simple, configured in src/main/resources/simplelogger.properties
    implementation(libs.slf4j.simple)

    // Fast Vaadin unit-testing with Karibu-Testing: https://github.com/mvysny/karibu-testing
    testImplementation(testFixtures(project(":vaadin-blocking-dialogs-loom")))
    testImplementation(libs.karibu.testing)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the loom strategy reflects into java.lang.ThreadBuilders$VirtualThreadBuilder (JDK-8308541)
val addOpens = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
tasks.withType<Test> { jvmArgs(addOpens) }
tasks.withType<JavaExec> { jvmArgs(addOpens) }

application {
    mainClass = "com.example.blockingdialogs.Main"
    applicationDefaultJvmArgs = addOpens
}
