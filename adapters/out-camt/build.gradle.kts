// Driven adapter: ISO 20022 camt.053 statements, read with the JDK's StAX parser.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
}
