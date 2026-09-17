package dev.abgleich.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rule 13: every bug ID has a test named after it before its feature is done. The catalogue in
 * {@code docs/bug-catalogue.md} and the test sources are compared on every build, so neither can drift.
 */
class BugCatalogueTest {

    private static final Path ROOT = Path.of("..");
    private static final Path CATALOGUE = ROOT.resolve("docs/bug-catalogue.md");
    private static final Pattern ROW = Pattern.compile("^\\| (B\\d{2}) \\|.*\\|([^|]*)\\|\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[[^]]+]\\(\\.\\./([^)]+)\\)");
    private static final Pattern TEST_METHOD = Pattern.compile("void (B\\d{2})_\\w+\\(");

    @Test
    void every_bug_from_B01_to_B45_is_listed_once_in_order() throws IOException {
        List<String> expected = IntStream.rangeClosed(1, 45).mapToObj(n -> String.format("B%02d", n)).toList();

        assertThat(catalogueRows().keySet()).containsExactlyElementsOf(expected);
    }

    @Test
    void every_bug_has_a_test_named_after_it() throws IOException {
        Map<String, Set<String>> tested = testFilesById();

        assertThat(catalogueRows().keySet()).allSatisfy(id ->
                assertThat(tested).as("a test method named %s_...", id).containsKey(id));
    }

    @Test
    void the_catalogue_links_exactly_the_test_classes_of_each_bug() throws IOException {
        Map<String, Set<String>> tested = testFilesById();

        catalogueRows().forEach((id, linked) -> {
            assertThat(linked).as("linked test classes of %s that exist", id)
                    .allSatisfy(file -> assertThat(ROOT.resolve(file)).exists());
            assertThat(linked).as("test classes of %s in docs/bug-catalogue.md", id)
                    .containsExactlyInAnyOrderElementsOf(tested.getOrDefault(id, Set.of()));
        });
    }

    /** Linked test files per ID, in catalogue order. */
    private static Map<String, Set<String>> catalogueRows() throws IOException {
        Map<String, Set<String>> rows = new TreeMap<>();
        for (String line : Files.readAllLines(CATALOGUE, StandardCharsets.UTF_8)) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                assertThat(rows).as("%s listed once", row.group(1)).doesNotContainKey(row.group(1));
                Set<String> files = new TreeSet<>();
                Matcher link = LINK.matcher(row.group(2));
                while (link.find()) {
                    files.add(link.group(1));
                }
                rows.put(row.group(1), files);
            }
        }
        return rows;
    }

    /** Test source files, relative to the repository root, that contain a test method for each ID. */
    private static Map<String, Set<String>> testFilesById() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String unix = path.toString().replace('\\', '/');
                        return (unix.contains("/src/test/java/") || unix.contains("/src/testFixtures/java/"))
                                && !unix.contains("/build/");
                    })
                    .flatMap(path -> ids(path).stream().map(id -> Map.entry(id,
                            ROOT.relativize(path).toString().replace('\\', '/'))))
                    .collect(Collectors.groupingBy(Map.Entry::getKey, TreeMap::new,
                            Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(TreeSet::new))));
        }
    }

    private static Set<String> ids(Path file) {
        try {
            Matcher method = TEST_METHOD.matcher(Files.readString(file, StandardCharsets.UTF_8));
            Set<String> ids = new TreeSet<>();
            while (method.find()) {
                ids.add(method.group(1));
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
