pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "abgleich"

include(
    "domain",
    "application",
    "adapters:out-camt",
    "adapters:out-postgres",
    "bootstrap",
    "architecture-tests",
)
