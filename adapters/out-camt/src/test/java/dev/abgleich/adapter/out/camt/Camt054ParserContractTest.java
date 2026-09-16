package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StatementParserContract;
import dev.abgleich.application.port.out.StatementParserPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

class Camt054ParserContractTest extends StatementParserContract {

    private static final byte[] FIXTURE = load("/fixtures/camt054/swiss-notification-2026-09-15.xml");

    @Override
    protected StatementParserPort parser() {
        return new Camt054Parser();
    }

    @Override
    protected StatementFormat format() {
        return StatementFormat.CAMT054_V08;
    }

    @Override
    protected byte[] validFile() {
        return FIXTURE.clone();
    }

    @Override
    protected byte[] validFileWithClosingBalanceOneCentHigher() {
        return null;
    }

    @Override
    protected boolean hasBalances() {
        return false;
    }

    private static byte[] load(String resource) {
        try (InputStream in = Camt054ParserContractTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
