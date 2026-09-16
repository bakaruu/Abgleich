package dev.abgleich.application.port.in;

/** The channel a statement file arrived through. */
public enum ImportSource {
    WEB,
    REST,
    SFTP,
    BANK_API
}
