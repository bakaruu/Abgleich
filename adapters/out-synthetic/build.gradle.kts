// Driven adapter: deterministic synthetic invoices and bank files (camt.053 and Norma 43) for examples and tests.
plugins {
    id("abgleich.java-conventions")
}

dependencies {
    implementation(project(":application"))
}

// ./gradlew evaluateMatching: precision per rule over the labelled dataset; fails on any wrong auto-confirmation.
tasks.register<JavaExec>("evaluateMatching") {
    group = "verification"
    description = "Runs the matcher over 300 labelled payments and prints precision and recall per rule."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.abgleich.adapter.out.synthetic.evaluation.EvaluateMatching"
}
