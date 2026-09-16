// A small stand-in for a bank's statement API, for local runs and tests. Not part of the application.
plugins {
    id("abgleich.java-conventions")
    application
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters:out-synthetic"))
}

application {
    mainClass = "dev.abgleich.mockbank.MockBank"
}
