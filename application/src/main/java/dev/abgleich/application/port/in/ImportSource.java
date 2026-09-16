package dev.abgleich.application.port.in;

/** The channel a statement file arrived through. */
public enum ImportSource {
    WEB,
    REST,
    SFTP,
    BANK_API,
    /** Synthetic example data loaded from the UI; the public demo purges it (B43). */
    EXAMPLE
}
