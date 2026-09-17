// Browser tests against a running Abgleich, local or the public demo. Not part of `build`: they need a server.
//
//   ./gradlew :smoke-tests:installBrowser                          # once: downloads Chromium for Playwright
//   ./gradlew :smoke-tests:smokeTest -Psmoke.baseUrl=http://localhost:8080
//   ./gradlew :smoke-tests:tour -Psmoke.baseUrl=http://localhost    # screenshots and GIF for the README
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    testImplementation(libs.playwright)
}

val baseUrl = providers.gradleProperty("smoke.baseUrl").orElse("http://localhost:8080")

tasks.test {
    enabled = false
}

tasks.register<Test>("smokeTest") {
    group = "verification"
    description = "Visitor journey in a real browser against -Psmoke.baseUrl."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("smoke.baseUrl", baseUrl.get())
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("installBrowser") {
    group = "verification"
    description = "Downloads Chromium for Playwright; on Linux CI also its system libraries."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    val withDeps = System.getProperty("os.name").lowercase().contains("linux")
    args = if (withDeps) listOf("install", "--with-deps", "chromium") else listOf("install", "chromium")
}

tasks.register<JavaExec>("tour") {
    group = "documentation"
    description = "Screenshots of every screen and an animated GIF under docs/images."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.abgleich.smoke.Tour"
    args = listOf(baseUrl.get(), rootProject.file("docs/images").absolutePath)
}
