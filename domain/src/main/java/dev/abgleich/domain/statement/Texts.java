package dev.abgleich.domain.statement;

final class Texts {

    private Texts() {
    }

    /** Trims and collapses inner whitespace; blank text becomes {@code null}. */
    static String blankToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip().replaceAll("\\s+", " ");
    }
}
