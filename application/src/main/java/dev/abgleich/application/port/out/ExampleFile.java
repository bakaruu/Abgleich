package dev.abgleich.application.port.out;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A synthetic bank statement file, byte for byte as a bank would send it.
 *
 * @param country ISO 3166 code of the bank's country, such as CH or ES
 */
public record ExampleFile(String name, String country, String description, byte[] content) {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,79}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    public ExampleFile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(content, "content");
        if (!COUNTRY.matcher(country).matches()) {
            throw new IllegalArgumentException("country must be an ISO 3166 code such as CH");
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Example file names use lower-case letters, digits, '.' and '-'");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExampleFile file && name.equals(file.name) && country.equals(file.country)
                && description.equals(file.description)
                && Arrays.equals(content, file.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, country, description, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "ExampleFile[" + name + ", " + content.length + " bytes]";
    }
}
