package dev.abgleich.adapter.out.norma43;

import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StatementParserContract;
import dev.abgleich.application.port.out.StatementParserPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

class Norma43ParserContractTest extends StatementParserContract {

    private static final byte[] FIXTURE = load("/fixtures/norma43/spain-two-accounts-2026-09-15.n43");

    @Override
    protected StatementParserPort parser() {
        return new Norma43Parser();
    }

    @Override
    protected StatementFormat format() {
        return StatementFormat.NORMA43;
    }

    @Override
    protected byte[] validFile() {
        return FIXTURE.clone();
    }

    @Override
    protected byte[] validFileWithClosingBalanceOneCentHigher() {
        // Closing balance of the first account: record 33, positions 60-73.
        String text = new String(FIXTURE, StandardCharsets.ISO_8859_1);
        return text.replaceFirst("(?m)^(33.{57})00000000826350", "$100000000826351")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] load(String resource) {
        try (InputStream in = Norma43ParserContractTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
