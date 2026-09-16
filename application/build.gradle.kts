// Use cases and ports. Pure Java: depends only on the domain (B36).
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    api(project(":domain"))
}
