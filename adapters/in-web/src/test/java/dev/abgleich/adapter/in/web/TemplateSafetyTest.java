package dev.abgleich.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TemplateSafetyTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Test
    void B32_templates_never_render_unescaped_text() throws IOException {
        assertThat(templates()).isNotEmpty().allSatisfy(template ->
                assertThat(Files.readString(template, StandardCharsets.UTF_8))
                        .as(template.toString())
                        .doesNotContain("th:utext")
                        .doesNotContain("[(${"));
    }

    @Test
    void B32_templates_have_no_inline_scripts_or_handlers_that_a_strict_csp_would_block() throws IOException {
        assertThat(templates()).allSatisfy(template -> {
            String html = Files.readString(template, StandardCharsets.UTF_8);
            assertThat(html).as(template.toString())
                    .doesNotContainPattern("<script(?![^>]*\\bsrc=)")
                    .doesNotContainPattern("\\son[a-z]+=")
                    .doesNotContain("style=\"")
                    .doesNotContain("hx-on");
        });
    }

    private static List<Path> templates() throws IOException {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(path -> path.toString().endsWith(".html")).toList();
        }
    }
}
