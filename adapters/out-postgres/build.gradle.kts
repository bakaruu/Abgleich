// Driven adapter: PostgreSQL persistence with Spring JDBC, and the Flyway migrations.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.postgresql:postgresql")

    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
