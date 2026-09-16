package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.port.out.ParsedStatementFile;
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
 * Secure streaming StAX parsing shared by the camt parsers: never a DOM (B14), DTDs and external
 * entities disabled and any document type declaration rejected (B08). The XML declaration decides
 * the charset, as the XML specification requires.
 */
final class CamtParser {

    private final CamtMessage message;
    private final XMLInputFactory factory = secureFactory();

    CamtParser(CamtMessage message) {
        this.message = message;
    }

    ParsedStatementFile parse(InputStream in) {
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
            return new CamtDocumentReader(xml, message).read();
        } catch (XMLStreamException e) {
            // The parser message can quote file content, so it is kept only as the cause (B41).
            throw new InvalidStatementException(Reason.MALFORMED_FILE,
                    "The file is not well-formed XML or is incomplete", e);
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
