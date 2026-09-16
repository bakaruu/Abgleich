// Driving adapter: imports statement files a bank drops into an SFTP folder (B25).
// Test fixtures hold an embedded SFTP server, shared with the end-to-end tests in bootstrap.
plugins {
    id("abgleich.java-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":application"))
    implementation("org.springframework.integration:spring-integration-sftp")
    implementation(libs.shedlock.spring)
    implementation("org.slf4j:slf4j-api")

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.sshd.sftp)
    testFixturesImplementation("org.springframework.integration:spring-integration-sftp")
}
