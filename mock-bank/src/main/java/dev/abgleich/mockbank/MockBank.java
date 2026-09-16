package dev.abgleich.mockbank;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.abgleich.adapter.out.synthetic.SyntheticExampleData;
import dev.abgleich.application.port.out.ExampleFile;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A stand-in for a bank's statement API, in the style of open banking APIs:
 *
 * <pre>
 * GET /v1/accounts/{iban}/statements?bookedFrom=2026-09-01   Authorization: Bearer {token}
 *     200 {"statements":[{"id":"...","bookingDate":"2026-09-15"}]}
 * GET /v1/statements/{id}                                     Authorization: Bearer {token}
 *     200 the statement file, byte for byte
 * </pre>
 *
 * Only the JDK HTTP server, so it starts in milliseconds inside tests. Only synthetic data (B41).
 *
 * <p>Run locally: {@code ./gradlew :mock-bank:run}. It serves the example files on port 8090 with the token
 * {@value #LOCAL_TOKEN}, booked today.
 */
public final class MockBank implements AutoCloseable {

    public static final String LOCAL_TOKEN = "mock-bank-local-only";

    private static final Pattern LIST = Pattern.compile("/v1/accounts/([A-Z0-9]{15,34})/statements");
    private static final Pattern DOWNLOAD = Pattern.compile("/v1/statements/([A-Za-z0-9-]{1,64})");

    private final HttpServer server;
    private final String token;
    private final Map<String, Published> statements = new ConcurrentHashMap<>();
    private final AtomicInteger listRequests = new AtomicInteger();
    private volatile boolean unavailable;

    private MockBank(HttpServer server, String token) {
        this.server = server;
        this.token = token;
    }

    /** @param port 0 for a random free port */
    public static MockBank start(int port, String token) {
        Objects.requireNonNull(token, "token");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            MockBank bank = new MockBank(server, token);
            server.createContext("/v1/", bank::handle);
            server.start();
            return bank;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(Objects.requireNonNullElse(System.getenv("MOCK_BANK_PORT"), "8090"));
        String token = Objects.requireNonNullElse(System.getenv("MOCK_BANK_TOKEN"), LOCAL_TOKEN);
        MockBank bank = start(port, token);
        LocalDate today = LocalDate.now();
        for (ExampleFile file : new SyntheticExampleData().exampleData().files()) {
            List<String> accounts = file.name().contains("norma43")
                    ? List.of("ES9121000418450200051332")
                    : List.of("CH4431999123000889012", "CH9300762011623852957");
            accounts.forEach(account -> bank.publish(account, today, file.content()));
        }
        System.out.println("Mock bank listening on " + bank.baseUrl() + " with the example statements, booked " + today);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Offers a statement file for the account; the same content for the same account keeps its id. */
    public String publish(String iban, LocalDate bookingDate, byte[] content) {
        String id = id(iban, content);
        statements.put(id, new Published(id, iban, bookingDate, content.clone()));
        return id;
    }

    /** Answers every request with 503 until set back, like a bank in maintenance. */
    public void unavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public int listRequests() {
        return listRequests.get();
    }

    public void clear() {
        statements.clear();
        listRequests.set(0);
        unavailable = false;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "Method not allowed");
                return;
            }
            if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "text/plain", "Missing or wrong token");
                return;
            }
            if (unavailable) {
                send(exchange, 503, "text/plain", "Maintenance");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Matcher list = LIST.matcher(path);
            Matcher download = DOWNLOAD.matcher(path);
            if (list.matches()) {
                listRequests.incrementAndGet();
                list(exchange, list.group(1));
            } else if (download.matches()) {
                Optional<Published> statement = Optional.ofNullable(statements.get(download.group(1)));
                if (statement.isPresent()) {
                    send(exchange, 200, "application/octet-stream", statement.get().content());
                } else {
                    send(exchange, 404, "text/plain", "No such statement");
                }
            } else {
                send(exchange, 404, "text/plain", "Not found");
            }
        }
    }

    private void list(HttpExchange exchange, String iban) throws IOException {
        LocalDate bookedFrom;
        try {
            bookedFrom = LocalDate.parse(queryParameter(exchange, "bookedFrom"));
        } catch (DateTimeParseException | NullPointerException e) {
            send(exchange, 400, "text/plain", "bookedFrom must be a date such as 2026-09-01");
            return;
        }
        String json = statements.values().stream()
                .filter(statement -> statement.iban().equals(iban) && !statement.bookingDate().isBefore(bookedFrom))
                .sorted(Comparator.comparing(Published::bookingDate).thenComparing(Published::id))
                .map(statement -> "{\"id\":\"" + statement.id() + "\",\"bookingDate\":\"" + statement.bookingDate() + "\"}")
                .collect(Collectors.joining(",", "{\"statements\":[", "]}"));
        send(exchange, 200, "application/json", json);
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String id(String iban, byte[] content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(iban.getBytes(StandardCharsets.US_ASCII));
            return "stmt-" + HexFormat.of().formatHex(sha256.digest(content)).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
        }
    }

    private record Published(String id, String iban, LocalDate bookingDate, byte[] content) {
    }
}
