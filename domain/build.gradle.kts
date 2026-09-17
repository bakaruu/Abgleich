// Pure Java: no framework dependencies allowed here (B36).
plugins {
    id("abgleich.java-conventions")
    alias(libs.plugins.pitest)
}

// Mutation testing: ./gradlew :domain:pitest changes the rules on purpose and fails if the tests do not notice.
pitest {
    pitestVersion = libs.versions.pitest.asProvider().get()
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf("dev.abgleich.domain.*")
    threads = 4
    timestampedReports = false
    outputFormats = listOf("HTML", "XML")
    // The score reached once the boundary tests were written. Dropping below it means a rule lost its safety net.
    mutationThreshold = 85
}
