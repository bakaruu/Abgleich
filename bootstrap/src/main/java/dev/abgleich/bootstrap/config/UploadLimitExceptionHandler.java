package dev.abgleich.bootstrap.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Oversized uploads are refused while the request is parsed, before any controller runs (B42).
 * The web UI gets a short fragment htmx can swap in; the API gets a Problem Detail.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadLimitExceptionHandler {

    static final String MESSAGE = "The file is larger than 20 MB. Nothing was stored.";

    @ExceptionHandler
    ResponseEntity<Object> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, MESSAGE);
            problem.setType(URI.create("urn:abgleich:problem:file-too-large"));
            problem.setTitle("File too large");
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        // A constant text: nothing from the request is echoed back.
        String html = "<div class=\"alert alert-error\" role=\"alert\"><strong>File rejected.</strong> "
                + "<span>" + MESSAGE + "</span></div>";
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).contentType(MediaType.TEXT_HTML).body(html);
    }
}
