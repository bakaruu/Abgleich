// Driving adapter: server-rendered UI with Thymeleaf and htmx. Calls use cases only (B40).
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    runtimeOnly(libs.htmx)
}
