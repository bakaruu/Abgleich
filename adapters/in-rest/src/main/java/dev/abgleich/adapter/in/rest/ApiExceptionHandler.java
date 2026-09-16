package dev.abgleich.adapter.in.rest;

import dev.abgleich.application.port.out.DuplicateInvoiceException;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.reference.InvalidReferenceException;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Errors as RFC 9457 Problem Details. Messages of domain exceptions are written to be shown; technical
 * failures get a generic text so no internal detail leaks.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
class ApiExceptionHandler {

    @ExceptionHandler
    ProblemDetail rejectedStatement(InvalidStatementException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "statement-rejected",
                "Statement rejected", e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    @ExceptionHandler({InvalidInvoiceException.class, InvalidReferenceException.class, BadRequestException.class,
            IllegalArgumentException.class})
    ProblemDetail invalidInput(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-input", "Invalid input", e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    ProblemDetail unreadableRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-input", "Invalid input",
                "The request body or the uploaded file is missing or malformed");
    }

    @ExceptionHandler
    ProblemDetail duplicateInvoice(DuplicateInvoiceException e) {
        return problem(HttpStatus.CONFLICT, "duplicate-invoice", "Invoice already exists", e.getMessage());
    }

    /** B34: a stale decision is 412 Precondition Failed, the standard answer to an outdated If-Match. */
    @ExceptionHandler
    ProblemDetail decisionRefused(DecisionRefusedException e) {
        return switch (e.result().outcome()) {
            case STALE -> problem(HttpStatus.PRECONDITION_FAILED, "stale-decision", "Changed by someone else", e.getMessage());
            case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
            case REFUSED -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "decision-refused", "Decision refused", e.getMessage());
            case DONE, ALREADY_DONE -> throw new IllegalStateException("A stored decision is not an error");
        };
    }

    @ExceptionHandler
    ProblemDetail preconditionRequired(ETags.PreconditionRequiredException e) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "version-required", "Version required", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidParameter(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-input", "Invalid input",
                "Parameter '" + e.getName() + "' has an invalid value");
    }

    @ExceptionHandler
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail storageUnavailable(StorageException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable", "Storage unavailable",
                "The request could not be completed. Please try again later.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:abgleich:problem:" + type.toLowerCase(Locale.ROOT)));
        problem.setTitle(title);
        return problem;
    }
}
