// Architecture rules enforced on every build. A violation fails CI.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation(project(":adapters:in-kafka"))
    testImplementation(project(":adapters:in-rest"))
    testImplementation(project(":adapters:in-scheduler"))
    testImplementation(project(":adapters:in-sftp"))
    testImplementation(project(":adapters:in-web"))
    testImplementation(project(":adapters:out-bank-api"))
    testImplementation(project(":adapters:out-camt"))
    testImplementation(project(":adapters:out-csv"))
    testImplementation(project(":adapters:out-kafka"))
    testImplementation(project(":adapters:out-norma43"))
    testImplementation(project(":adapters:out-postgres"))
    testImplementation(project(":adapters:out-synthetic"))
    testImplementation(project(":bootstrap"))
    testImplementation(libs.archunit.junit5)
}
