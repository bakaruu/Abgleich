package dev.abgleich.application.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The example files are downloadable, so their names end up in a URL and in a {@code Content-Disposition}
 * header: a name is only ever lower-case letters, digits, dots and dashes, never a path (B32).
 */
class ExampleFileTest {

    private static final byte[] CONTENT = "<Document/>".getBytes(StandardCharsets.UTF_8);

    @Test
    void two_files_are_the_same_when_their_bytes_are() {
        ExampleFile file = new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", CONTENT);
        ExampleFile same = new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", CONTENT.clone());

        assertThat(file).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(file).isEqualTo(file).isNotEqualTo(null).isNotEqualTo("swiss.xml");
    }

    @Test
    void a_different_name_country_description_or_content_makes_a_different_file() {
        ExampleFile file = new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", CONTENT);

        assertThat(file).isNotEqualTo(new ExampleFile("spanish.n43", "CH", "A Swiss camt.053", CONTENT));
        assertThat(file).isNotEqualTo(new ExampleFile("swiss.xml", "ES", "A Swiss camt.053", CONTENT));
        assertThat(file).isNotEqualTo(new ExampleFile("swiss.xml", "CH", "Another file", CONTENT));
        assertThat(file).isNotEqualTo(new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", "other".getBytes()));
    }

    @Test
    void the_content_cannot_be_changed_from_outside() {
        byte[] mutable = "<Document/>".getBytes(StandardCharsets.UTF_8);
        ExampleFile file = new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", mutable);

        mutable[0] = 'X';
        file.content()[1] = 'Y';

        assertThat(file.content()).isEqualTo(CONTENT);
    }

    @Test
    void a_name_that_could_walk_out_of_the_folder_is_refused() {
        assertThatThrownBy(() -> new ExampleFile("../../etc/passwd", "CH", "nope", CONTENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case letters");
        assertThatThrownBy(() -> new ExampleFile("Swiss.XML", "CH", "nope", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExampleFile("", "CH", "nope", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_country_is_an_iso_code() {
        assertThatThrownBy(() -> new ExampleFile("swiss.xml", "Switzerland", "nope", CONTENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 3166");
        assertThatThrownBy(() -> new ExampleFile("swiss.xml", "ch", "nope", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void B41_to_string_says_the_size_not_the_contents() {
        ExampleFile file = new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", CONTENT);

        assertThat(file).hasToString("ExampleFile[swiss.xml, 11 bytes]");
    }
}
