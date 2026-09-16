package dev.abgleich.application.port.in;

import java.util.Objects;

public record ImportStatementCommand(ImportSource source, StatementContent content) {

    public ImportStatementCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(content, "content");
    }
}
