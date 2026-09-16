// Driven adapter: Spanish Norma 43 (AEB cuaderno 43) fixed-width statements.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))

    testImplementation(testFixtures(project(":application")))
}
