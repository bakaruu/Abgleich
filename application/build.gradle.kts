// Use cases and ports. Pure Java: depends only on the domain (B36).
// Test fixtures hold contract suites that every adapter of a port must pass.
plugins {
    id("abgleich.java-conventions")
    `java-test-fixtures`
    alias(libs.plugins.pitest)
}

dependencies {
    api(project(":domain"))

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
    testFixturesApi(libs.jqwik)
}

// Mutation testing: ./gradlew :application:pitest breaks the use cases on purpose and fails if no test notices.
pitest {
    pitestVersion = libs.versions.pitest.asProvider().get()
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf("dev.abgleich.application.*")
    threads = 4
    timestampedReports = false
    outputFormats = listOf("HTML", "XML")
    // Lower than the domain on purpose: some use cases are only exercised end to end, from `bootstrap`.
    mutationThreshold = 82
}
