// The only module that knows every other module: Spring Boot wiring, configuration, profiles.
plugins {
    id("abgleich.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters:in-kafka"))
    implementation(project(":adapters:in-rest"))
    implementation(project(":adapters:in-scheduler"))
    implementation(project(":adapters:in-sftp"))
    implementation(project(":adapters:in-web"))
    implementation(project(":adapters:out-bank-api"))
    implementation(project(":adapters:out-camt"))
    implementation(project(":adapters:out-csv"))
    implementation(project(":adapters:out-kafka"))
    implementation(project(":adapters:out-norma43"))
    implementation(project(":adapters:out-postgres"))
    implementation(project(":adapters:out-synthetic"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.integration:spring-integration-sftp")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(testFixtures(project(":adapters:in-sftp")))
    testImplementation(project(":mock-bank"))
}

// End-to-end tests read the byte-exact fixtures owned by the parser modules instead of keeping copies.
sourceSets.test {
    resources.srcDir(project(":adapters:out-camt").file("src/test/resources"))
    resources.srcDir(project(":adapters:out-norma43").file("src/test/resources"))
}

// -Pgolden.update=true rewrites the golden files instead of comparing against them.
tasks.test {
    systemProperty("golden.update", providers.gradleProperty("golden.update").getOrElse("false"))
}

// A fixed name, so the container image build does not depend on the version.
tasks.bootJar {
    archiveFileName = "abgleich.jar"
}

// Measured, not guessed: ./gradlew benchmark -Pbenchmark.transactions=20000. Not part of `build`: it takes
// minutes and its numbers belong to the machine that ran it.
tasks.test {
    filter.excludeTestsMatching("dev.abgleich.bootstrap.PerformanceBenchmark")
}

tasks.register<Test>("benchmark") {
    group = "verification"
    description = "Times parsing, invoice registration and a full import of a large statement."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.abgleich.bootstrap.PerformanceBenchmark")
    systemProperty("benchmark.transactions",
            providers.gradleProperty("benchmark.transactions").getOrElse("5000"))
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}
