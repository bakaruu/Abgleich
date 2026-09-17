package dev.abgleich.application.statement.port.out;

import dev.abgleich.application.statement.StatementContent;
import dev.abgleich.domain.account.Iban;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Statement files a bank offers through its API. */
public interface BankStatementFetchPort {

    /**
     * Statements of the account booked on or after {@code since}. The content of each one is downloaded
     * only when it is opened.
     *
     * @throws BankUnavailableException if the bank cannot be reached or refuses the request
     */
    List<RemoteStatement> listSince(Iban account, LocalDate since);

    /**
     * @param id the bank's identifier of the file, used only to download it
     * @param content opening it may also throw {@link BankUnavailableException}
     */
    record RemoteStatement(String id, LocalDate bookingDate, StatementContent content) {

        private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

        public RemoteStatement {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bookingDate, "bookingDate");
            Objects.requireNonNull(content, "content");
            if (!SAFE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("A statement id has 1 to 64 letters, digits or '-'");
            }
        }
    }
}
