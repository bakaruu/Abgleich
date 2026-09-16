// Driven adapter: statements in the documented Abgleich CSV format, read with Apache Commons CSV.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.commons.csv)

    testImplementation(testFixtures(project(":application")))
}
