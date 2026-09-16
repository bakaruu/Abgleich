package dev.abgleich.adapter.out.csv;

import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StatementParserContract;
import dev.abgleich.application.port.out.StatementParserPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

class CsvStatementParserContractTest extends StatementParserContract {

    static final byte[] FIXTURE = load("/fixtures/csv/swiss-account-2026-09-15.csv");

    @Override
    protected StatementParserPort parser() {
        return new CsvStatementParser();
    }

    @Override
    protected StatementFormat format() {
        return StatementFormat.CSV;
    }

    @Override
    protected byte[] validFile() {
        return FIXTURE.clone();
    }

    @Override
    protected byte[] validFileWithClosingBalanceOneCentHigher() {
        return new String(FIXTURE, StandardCharsets.UTF_8).replace("closing=4648.00", "closing=4648.01")
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] load(String resource) {
        try (InputStream in = CsvStatementParserContractTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
