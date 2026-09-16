package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.port.out.ParsedStatementFile;
import dev.abgleich.application.port.out.StatementParserPort;
import dev.abgleich.application.port.out.StatementSniff;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses ISO 20022 camt.053.001.04 bank-to-customer statements (Swiss Payment Standards, SEPA).
 *
 * <p>The file is read as a stream with StAX, never loaded as a DOM (B14). DTDs and external
 * entities are disabled (B08) and any document type declaration is rejected. The XML declaration
 * decides the charset, as the XML specification requires.
 */
public final class Camt053Parser implements StatementParserPort {

    static final String NAMESPACE_PREFIX = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.";
    static final String NAMESPACE_V04 = NAMESPACE_PREFIX + "04";

    private final XMLInputFactory factory = secureFactory();

    @Override
    public boolean canParse(StatementSniff sniff) {
        return sniff.containsAscii(NAMESPACE_PREFIX);
    }

    @Override
    public ParsedStatementFile parse(InputStream in) {
        Objects.requireNonNull(in, "in");
        XMLStreamReader xml = null;
        try {
            // The JDK parser closes its input at the end of the document; the caller owns the stream.
            xml = factory.createXMLStreamReader(new FilterInputStream(in) {
                @Override
                public void close() {
                    // left open on purpose
                }
            });
            return new Camt053DocumentReader(xml).read();
        } catch (XMLStreamException e) {
            // The parser message can quote file content, so it is kept only as the cause (B41).
            throw new InvalidStatementException(Reason.MALFORMED_FILE,
                    "The camt.053 file is not well-formed XML or is incomplete", e);
        } finally {
            closeQuietly(xml);
        }
    }

    private static XMLInputFactory secureFactory() {
        // The JDK implementation, not whatever StAX provider happens to be on the classpath.
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        return factory;
    }

    private static void closeQuietly(XMLStreamReader xml) {
        if (xml == null) {
            return;
        }
        try {
            xml.close();
        } catch (XMLStreamException ignored) {
            // Nothing left to read; the caller closes the underlying stream.
        }
    }
}
