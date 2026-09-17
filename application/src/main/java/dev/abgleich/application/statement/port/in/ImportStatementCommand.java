package dev.abgleich.application.statement.port.in;

import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.StatementContent;
import java.util.Objects;

public record ImportStatementCommand(ImportSource source, StatementContent content) {

    public ImportStatementCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(content, "content");
    }
}
