// Driven adapter: HTTP client of the bank's statement API.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework:spring-web")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(project(":mock-bank"))
}
