package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes Norma 43 files as Spanish banks do: 80-character records in ISO-8859-1, CRLF line endings,
 * totals per account in record 33 and the record count in record 88.
 */
final class Norma43Writer {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String EUR_NUMERIC = "978";

    private final LocalDate day;
    private final List<Account> accounts = new ArrayList<>();

    Norma43Writer(LocalDate day) {
        this.day = day;
    }

    Account account(String bank, String branch, String number, Money opening, String holder) {
        Account account = new Account(bank, branch, number, opening, holder);
        accounts.add(account);
        return account;
    }

    byte[] toBytes() {
        List<String> records = new ArrayList<>();
        accounts.forEach(account -> account.appendTo(records));
        records.add("88" + "9".repeat(18) + number(records.size(), 6) + " ".repeat(54));
        String text = String.join("\r\n", records) + "\r\n";
        try {
            ByteBuffer bytes = StandardCharsets.ISO_8859_1.newEncoder()
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Example text must be representable in ISO-8859-1", e);
        }
    }

    final class Account {
        private final String bank;
        private final String branch;
        private final String number;
        private final Money opening;
        private final String holder;
        private final List<String> movements = new ArrayList<>();
        private Money closing;
        private Money debits;
        private Money credits;
        private int debitCount;
        private int creditCount;

        private Account(String bank, String branch, String number, Money opening, String holder) {
            this.bank = bank;
            this.branch = branch;
            this.number = number;
            this.opening = opening;
            this.holder = holder;
            this.closing = opening;
            this.debits = Money.zero(opening.currency());
            this.credits = Money.zero(opening.currency());
        }

        /** A credit transfer: concept "04"; reference 2 carries an RF reference when the payer gave one. */
        Account credit(Money amount, String reference2, String... concepts) {
            credits = credits.add(amount);
            creditCount++;
            closing = closing.add(amount);
            return movement("04", Direction.CREDIT, amount, 0, reference2, concepts);
        }

        /** A bank charge: concept "17" (interest, fees and taxes) with a bank document number. */
        Account charge(Money amount, long documentNumber, String... concepts) {
            debits = debits.add(amount);
            debitCount++;
            closing = closing.subtract(amount);
            return movement("17", Direction.DEBIT, amount, documentNumber, null, concepts);
        }

        private Account movement(String commonConcept, Direction direction, Money amount, long documentNumber,
                String reference2, String... concepts) {
            movements.add("22" + " ".repeat(4) + branch + date() + date() + commonConcept + "000"
                    + (direction == Direction.DEBIT ? "1" : "2") + cents(amount, 14)
                    + number(documentNumber, 10) + "0".repeat(12) + text(reference2 == null ? "" : reference2, 16));
            for (int i = 0; i < concepts.length; i += 2) {
                String second = i + 1 < concepts.length ? concepts[i + 1] : "";
                movements.add("23" + number(i / 2 + 1, 2) + text(concepts[i], 38) + text(second, 38));
            }
            return this;
        }

        private void appendTo(List<String> records) {
            records.add("11" + bank + branch + number + date() + date() + sign(opening) + cents(abs(opening), 14)
                    + EUR_NUMERIC + "3" + text(holder, 26) + "   ");
            records.addAll(movements);
            records.add("33" + bank + branch + number + number(debitCount, 5) + cents(debits, 14)
                    + number(creditCount, 5) + cents(credits, 14) + sign(closing) + cents(abs(closing), 14)
                    + EUR_NUMERIC + "    ");
        }

        private String date() {
            return day.format(YYMMDD);
        }
    }

    private static String sign(Money money) {
        return money.isNegative() ? "1" : "2";
    }

    private static Money abs(Money money) {
        return money.isNegative() ? Money.zero(money.currency()).subtract(money) : money;
    }

    /** Two implied decimals: 1815.00 becomes "00000000181500" (B18). */
    private static String cents(Money money, int width) {
        return number(money.amount().unscaledValue().longValueExact(), width);
    }

    private static String number(long value, int width) {
        String digits = Long.toString(value);
        if (digits.length() > width) {
            throw new IllegalArgumentException(value + " does not fit in " + width + " digits");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    private static String text(String value, int width) {
        String fitted = value.length() > width ? value.substring(0, width) : value;
        return fitted + " ".repeat(width - fitted.length());
    }
}
