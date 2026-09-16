package dev.abgleich.adapter.in.rest;

/** Versions travel as strong entity tags: {@code ETag: "3"} and {@code If-Match: "3"}. */
final class ETags {

    private ETags() {
    }

    static String of(long version) {
        return "\"" + version + "\"";
    }

    /** @throws PreconditionRequiredException when the header is missing or not a version tag */
    static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send the version you saw in the If-Match header, for example If-Match: \"3\"");
        }
        String value = ifMatch.strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new PreconditionRequiredException("If-Match must hold a version such as \"3\"");
        }
    }

    static final class PreconditionRequiredException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        PreconditionRequiredException(String message) {
            super(message);
        }
    }
}
