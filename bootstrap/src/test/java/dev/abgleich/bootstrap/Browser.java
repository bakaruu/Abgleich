package dev.abgleich.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal HTTP client that keeps cookies like a browser session, so tests go through the real
 * servlet container, Spring Security and multipart parsing.
 */
final class Browser {

    private static final Pattern CSRF_META = Pattern.compile("<meta name=\"csrf-token\" content=\"([^\"]+)\"");

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    Browser(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    /** Opens the upload page and returns the CSRF token of this session. */
    String csrfToken() {
        Matcher matcher = CSRF_META.matcher(get("/").body());
        if (!matcher.find()) {
            throw new IllegalStateException("The page has no CSRF token");
        }
        return matcher.group(1);
    }

    HttpResponse<String> postJson(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /**
     * @param fields extra form fields, such as {@code _csrf}
     * @param headers extra headers, such as {@code HX-Request} or {@code X-CSRF-TOKEN}
     */
    HttpResponse<String> upload(String path, byte[] file, Map<String, String> fields, Map<String, String> headers) {
        String boundary = "abgleich-test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fields.forEach((name, value) -> write(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n"));
        write(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"statement\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n");
        body.writeBytes(file);
        write(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        headers.forEach(request::header);
        return send(request);
    }

    static Map<String, String> map(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
