package dev.abgleich.application.port.in;

import java.io.IOException;
import java.io.InputStream;

/** The bytes of an uploaded statement file. The caller of {@link #open()} closes the stream. */
@FunctionalInterface
public interface StatementContent {

    InputStream open() throws IOException;
}
