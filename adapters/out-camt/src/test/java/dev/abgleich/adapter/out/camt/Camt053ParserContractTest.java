package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StatementParserContract;
import dev.abgleich.application.port.out.StatementParserPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

class Camt053ParserContractTest extends StatementParserContract {

    private static final byte[] FIXTURE = load("/fixtures/camt053/swiss-day-2026-09-15.xml");

    @Override
    protected StatementParserPort parser() {
        return new Camt053Parser();
    }

    @Override
    protected StatementFormat format() {
        return StatementFormat.CAMT053_V04;
    }

    @Override
    protected byte[] validFile() {
        return FIXTURE.clone();
    }

    @Override
    protected byte[] validFileWithClosingBalanceOneCentHigher() {
        // The first CLBD balance of the fixture belongs to the first statement.
        String xml = new String(FIXTURE, StandardCharsets.UTF_8);
        return xml.replaceFirst("<Amt Ccy=\"CHF\">14238.00</Amt>", "<Amt Ccy=\"CHF\">14238.01</Amt>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] load(String resource) {
        try (InputStream in = Camt053ParserContractTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
