package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The files the public demo is deployed with. Tests run in bootstrap/, so the repository root is its parent. */
class DemoDeploymentTest {

    private static final Path ROOT = Path.of("..");
    private static final Pattern REQUIRED_VARIABLE = Pattern.compile("\\$\\{[A-Z_]+:\\?[^}]+}");

    @Test
    void B44_only_the_reverse_proxy_is_reachable_from_outside() throws IOException {
        Map<String, Map<String, Object>> services = services();

        assertThat(services).containsKeys("caddy", "app", "postgres", "kafka", "prometheus", "grafana");
        services.forEach((name, service) -> {
            if (name.equals("caddy")) {
                assertThat(service.get("ports")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .containsExactlyInAnyOrder("80:80", "443:443", "443:443/udp");
            } else {
                assertThat(service).as(name + " publishes no port").doesNotContainKey("ports");
            }
        });
        assertThat(environment(services.get("app"))).containsEntry("SPRING_PROFILES_ACTIVE", "demo");
        assertThat(Files.readString(ROOT.resolve("deploy/Caddyfile"))).contains("reverse_proxy app:8080")
                .doesNotContain("app:8081");
    }

    @Test
    void B45_secrets_come_from_an_env_file_that_git_ignores() throws IOException {
        Map<String, Map<String, Object>> services = services();

        services.forEach((name, service) -> environment(service).forEach((key, value) -> {
            if (key.contains("PASSWORD")) {
                assertThat(value).as(name + " " + key + " has no default in the repository").matches(REQUIRED_VARIABLE);
            }
        }));
        assertThat(Files.readAllLines(ROOT.resolve(".gitignore"))).contains(".env");
        assertThat(Files.readAllLines(ROOT.resolve(".dockerignore"))).contains("*", "!bootstrap/build/libs/abgleich.jar");
        assertThat(Files.readAllLines(ROOT.resolve("deploy/.env.example")))
                .filteredOn(line -> line.contains("PASSWORD="))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).endsWith("="));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws IOException {
        try (Reader reader = Files.newBufferedReader(ROOT.resolve("deploy/compose.demo.yaml"), StandardCharsets.UTF_8)) {
            Map<String, Object> compose = new Yaml().load(reader);
            return (Map<String, Map<String, Object>>) compose.get("services");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(Map<String, Object> service) {
        Object environment = service.getOrDefault("environment", Map.of());
        if (environment instanceof List<?>) {
            throw new AssertionError("Use the map form for environment variables");
        }
        return ((Map<String, Object>) environment).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }
}
