package dev.abgleich.application.port.out;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** A synthetic bank statement file, byte for byte as a bank would send it. */
public record ExampleFile(String name, String description, byte[] content) {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,79}");

    public ExampleFile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(content, "content");
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
        return other instanceof ExampleFile file && name.equals(file.name) && description.equals(file.description)
                && Arrays.equals(content, file.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "ExampleFile[" + name + ", " + content.length + " bytes]";
    }
}
