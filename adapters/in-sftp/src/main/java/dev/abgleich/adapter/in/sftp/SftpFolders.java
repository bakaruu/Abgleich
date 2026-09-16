package dev.abgleich.adapter.in.sftp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Remote folders of the statement drop.
 *
 * @param inbox where the bank uploads files and their {@code .done} markers
 * @param processed where imported files are moved, including files that had been imported before
 * @param error where rejected files are moved, each with a {@code .error.txt} file stating why
 */
public record SftpFolders(String inbox, String processed, String error) {

    private static final Pattern SAFE_PATH = Pattern.compile("/?[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

    public SftpFolders {
        requireSafe(inbox, "inbox");
        requireSafe(processed, "processed");
        requireSafe(error, "error");
        if (inbox.equals(processed) || inbox.equals(error) || processed.equals(error)) {
            throw new IllegalArgumentException("inbox, processed and error must be different folders");
        }
    }

    public static SftpFolders under(String root) {
        String base = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        return new SftpFolders(base + "/inbox", base + "/processed", base + "/error");
    }

    private static void requireSafe(String path, String name) {
        Objects.requireNonNull(path, name);
        if (!SAFE_PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException("Folder '" + name + "' must be a plain path without '..'");
        }
    }
}
