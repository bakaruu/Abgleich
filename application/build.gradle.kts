// Use cases and ports. Pure Java: depends only on the domain (B36).
// Test fixtures hold contract suites that every adapter of a port must pass.
plugins {
    id("abgleich.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":domain"))

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
    testFixturesApi(libs.jqwik)
}
