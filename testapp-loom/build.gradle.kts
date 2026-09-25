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
    // the demo itself; this module adds only the runner, its servlet and the launcher
    implementation(project(":testapp-common"))
    implementation(project(":vaadin-uifiber-loom"))

    // Vaadin
    implementation(libs.vaadin.core)
    if (!vaadin.effective.productionMode.get()) {
        implementation(libs.vaadin.dev)
    }
    implementation(libs.vaadin.boot)

    // logging: SLF4J API to SLF4J-Simple, configured in src/main/resources/simplelogger.properties
    implementation(libs.slf4j.simple)

    // the demo's tests, run here on the loom runner: LoomDemoTest extends DemoTest
    testImplementation(testFixtures(project(":testapp-common")))
    testImplementation(testFixtures(project(":vaadin-uifiber-loom")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the loom runner reflects into java.lang.ThreadBuilders$VirtualThreadBuilder (JDK-8308541)
val addOpens = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
tasks.withType<Test> { jvmArgs(addOpens) }
tasks.withType<JavaExec> { jvmArgs(addOpens) }

application {
    mainClass = "com.example.blockingdialogs.loom.Main"
    applicationDefaultJvmArgs = addOpens
}
