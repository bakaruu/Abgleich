package dev.abgleich.adapter.in.sftp;

/** The SFTP server could not be reached or refused a file operation. Files stay where they are. */
public final class SftpUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SftpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
