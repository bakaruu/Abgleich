package dev.abgleich.domain.invoice;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An invoice number, normalized so that "fv-2026-0087 " and "FV-2026-0087" are the same invoice
 * (B05). Up to 35 characters, the size of every reference field in ISO 20022.
 */
public record InvoiceNumber(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9 ./_-]{0,34}");

    public InvoiceNumber {
        Objects.requireNonNull(value, "value");
        value = value.strip().replaceAll("\s+", " ").toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidInvoiceException(
                    "Invoice number must have 1 to 35 characters: letters, digits, spaces, '.', '/', '_' or '-'");
        }
    }

    public static InvoiceNumber of(String value) {
        return new InvoiceNumber(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
