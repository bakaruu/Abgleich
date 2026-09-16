package dev.abgleich.domain.matching.text;

import dev.abgleich.domain.invoice.InvoiceNumber;
import java.util.List;
import java.util.Set;

/**
 * Finds invoice numbers in remittance texts written by people: "TRANSF TALLERES RUIZ SL FRA 87",
 * "Rechnung 143", "PAGO FACTURAS 91 Y 92", "FV2026-0087".
 *
 * <p>A short number only counts after a word that announces an invoice, so the "87" of an amount or a
 * date is never taken for invoice 87.
 */
public final class InvoiceMentions {

    private static final Set<String> KEYWORDS = Set.of(
            "FRA", "FRAS", "FACT", "FACTURA", "FACTURAS", "FAC", "FTRA", "FTRAS",
            "RECHNUNG", "RECHNUNGEN", "RG", "RE", "RECHNR", "RGNR",
            "INVOICE", "INVOICES", "INV", "FACTURE", "FACTURES", "FATTURA", "FATTURE", "NR", "NO", "NUM", "N");
    private static final Set<String> CONNECTORS = Set.of("Y", "E", "UND", "AND", "ET", "U");
    private static final int MAX_NUMBERS_AFTER_KEYWORD = 5;

    private InvoiceMentions() {
    }

    /** Whether the text names this invoice, by its full number or by its number after an invoice word. */
    public static boolean mentions(String text, InvoiceNumber number) {
        String normalized = TextSimilarity.normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        String compactNumber = number.value().replaceAll("[^A-Z0-9]", "");
        if (normalized.replace(" ", "").contains(compactNumber)) {
            return true;
        }
        String serial = serial(number);
        return !serial.isEmpty() && announcedNumbers(normalized).contains(serial);
    }

    /** The trailing digits of an invoice number without leading zeros: "FV-2026-0087" gives "87". */
    static String serial(InvoiceNumber number) {
        String digits = number.value().replaceAll("^.*?(\\d+)$", "$1");
        if (digits.equals(number.value()) && !digits.chars().allMatch(Character::isDigit)) {
            return "";
        }
        return stripZeros(digits);
    }

    private static List<String> announcedNumbers(String normalized) {
        String[] tokens = normalized.split(" ");
        List<String> numbers = new java.util.ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if (!KEYWORDS.contains(tokens[i])) {
                continue;
            }
            int taken = 0;
            for (int j = i + 1; j < tokens.length && taken < MAX_NUMBERS_AFTER_KEYWORD; j++) {
                String token = tokens[j];
                if (token.chars().allMatch(Character::isDigit)) {
                    numbers.add(stripZeros(token));
                    taken++;
                } else if (!CONNECTORS.contains(token)) {
                    break;
                }
            }
        }
        return numbers;
    }

    private static String stripZeros(String digits) {
        String stripped = digits.replaceFirst("^0+(?=\\d)", "");
        return stripped;
    }
}
