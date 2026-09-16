// Driven adapter: PostgreSQL persistence and Flyway migrations.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))

    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
