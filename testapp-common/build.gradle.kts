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
    // the demo is written against the runner-neutral API alone; each app adds its runner
    api(project(":vaadin-blocking-dialogs"))
    api(libs.vaadin.core)
    compileOnly(libs.jakarta.servlet)  // each app's servlet container brings it
    implementation(libs.slf4j.api)

    // DemoTest, the scenario tests every app runs by extending it on its own runner
    testFixturesApi(libs.karibu.testing)
    testFixturesApi(libs.junit)
}

// the demo's own sources, shown on its pages by SourceCode
tasks.processResources {
    from("src/main/java") {
        include("**/*.java")
        into("demo-sources")
    }
}
