// Driven adapter: publishes invoice events from the outbox to Kafka as JSON (B23).
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework.kafka:spring-kafka")
    implementation("tools.jackson.core:jackson-databind")
}
