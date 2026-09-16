pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "abgleich"

include(
    "domain",
    "application",
    "adapters:in-rest",
    "adapters:in-web",
    "adapters:out-camt",
    "adapters:out-csv",
    "adapters:out-norma43",
    "adapters:out-postgres",
    "adapters:out-synthetic",
    "bootstrap",
    "architecture-tests",
)
