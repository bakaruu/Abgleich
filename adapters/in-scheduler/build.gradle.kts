// Driving adapter: time triggers the bank download and the outbox relay. One instance at a time (B26).
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework:spring-context")
    implementation(libs.shedlock.spring)
    implementation("org.slf4j:slf4j-api")
}
