pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "abgleich"

include(
    "domain",
    "application",
    "adapters:in-kafka",
    "adapters:in-rest",
    "adapters:in-scheduler",
    "adapters:in-sftp",
    "adapters:in-web",
    "adapters:out-bank-api",
    "adapters:out-camt",
    "adapters:out-csv",
    "adapters:out-kafka",
    "adapters:out-norma43",
    "adapters:out-postgres",
    "adapters:out-synthetic",
    "bootstrap",
    "architecture-tests",
    "mock-bank",
    "smoke-tests",
)
