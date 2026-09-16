// Driving adapter: registers invoices from InvoiceCreated events. Calls use cases only (B40).
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework.kafka:spring-kafka")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
