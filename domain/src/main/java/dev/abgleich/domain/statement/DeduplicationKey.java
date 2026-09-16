package dev.abgleich.domain.statement;

import dev.abgleich.domain.reference.PaymentReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies a bank entry within its account, so the same movement is stored only once even if
 * it arrives in several files. The database enforces uniqueness per account (B12, B21).
 *
 * <p>When the bank gives a unique reference (camt {@code AcctSvcrRef}) the key is that reference.
 * Norma 43 has none, so the key is a hash of every field of the entry plus an ordinal among
 * identical entries of the same statement. Two real transfers of 605,00 € with the same concept on
 * the same day therefore get different keys and both are kept (B16), while the same file imported
 * again produces the same keys.
 */
public record DeduplicationKey(String value) {

    public static final int MAX_LENGTH = 128;
    /** Room left for the "#n" suffix of batch transactions. */
    private static final int MAX_ENTRY_KEY_LENGTH = MAX_LENGTH - 8;

    public DeduplicationKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Deduplication key must have between 1 and " + MAX_LENGTH + " characters");
        }
    }

    /**
     * The key of one payment inside an entry. An entry with several transactions (B09) is stored
     * as one row per transaction, so each one needs its own key: {@code <entry key>#<number>}.
     */
    public DeduplicationKey forTransaction(int number, int transactionsInEntry) {
        if (number < 1 || number > transactionsInEntry) {
            throw new IllegalArgumentException("Transaction number must be between 1 and " + transactionsInEntry);
        }
        return transactionsInEntry == 1 ? this : new DeduplicationKey(value + "#" + number);
    }

    /** One key per entry, in the same order as {@link Statement#entries()}. */
    static List<DeduplicationKey> forEntries(List<StatementEntry> entries) {
        Map<String, Integer> seen = new HashMap<>();
        List<DeduplicationKey> keys = new ArrayList<>(entries.size());
        for (StatementEntry entry : entries) {
            if (entry.bankReference() != null) {
                String key = "BANK:" + entry.bankReference();
                keys.add(new DeduplicationKey(key.length() <= MAX_ENTRY_KEY_LENGTH ? key : "BANK:" + sha256(key)));
                continue;
            }
            String hash = sha256(fingerprint(entry));
            int ordinal = seen.merge(hash, 1, Integer::sum);
            keys.add(new DeduplicationKey("DERIVED:" + hash + ":" + ordinal));
        }
        return List.copyOf(keys);
    }

    private static String fingerprint(StatementEntry entry) {
        StringBuilder fields = new StringBuilder()
                .append(entry.bookingDate()).append(SEPARATOR)
                .append(entry.valueDate()).append(SEPARATOR)
                .append(entry.direction()).append(SEPARATOR)
                .append(entry.amount()).append(SEPARATOR)
                .append(entry.reversal());
        for (TransactionDetail detail : entry.details()) {
            fields.append(SEPARATOR).append(detail.amount())
                    .append(SEPARATOR).append(describe(detail.reference()))
                    .append(SEPARATOR).append(detail.remittanceText())
                    .append(SEPARATOR).append(detail.counterpartyName())
                    .append(SEPARATOR).append(detail.endToEndId())
                    .append(SEPARATOR).append(detail.bankReference());
        }
        return fields.toString();
    }

    private static final char SEPARATOR = '';

    private static String describe(PaymentReference reference) {
        return switch (reference) {
            case PaymentReference.Qrr qrr -> "QRR:" + qrr.value().value();
            case PaymentReference.Scor scor -> "SCOR:" + scor.value().value();
            case PaymentReference.FreeText text -> "TEXT:" + text.value();
            case PaymentReference.None none -> "NONE";
        };
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
        }
    }
}
