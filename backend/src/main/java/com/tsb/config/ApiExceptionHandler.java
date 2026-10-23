package com.tsb.config;

import com.tsb.auth.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Turns errors into {@code {code, message}} JSON right where they happen.
 *
 * <p>Without this, Spring forwards an error to its {@code /error} page, and
 * because that forward is itself a request the security chain looks at, the
 * helpful sentence ("Orders must be worth at least 5 USDT") was stripped on
 * the way, leaving the browser with a bare {@code "Bad Request"}. Answering
 * here means the message the service wrote is the message the user reads.
 *
 * <p>Lowest precedence, so the auth endpoints' own handler (scoped to
 * {@code AuthController}) still wins there.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String code, String message) {
    }

    /** A sign-in problem met outside the auth endpoints (a token for an
     *  account that no longer exists, say) is still a 401, not a 500. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiError> auth(AuthException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getCode(), e.getMessage()));
    }

    /** Spring's own 4xx errors keep their status. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiError> framework(ErrorResponseException e) {
        HttpStatus s = HttpStatus.resolve(e.getStatusCode().value());
        String detail = e.getBody().getDetail();
        return ResponseEntity.status(e.getStatusCode()).body(new ApiError(
                s == null ? String.valueOf(e.getStatusCode().value()) : s.name(),
                detail == null || detail.isBlank() ? "The request could not be completed." : detail));
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(TypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_REQUEST", "A value in the request has the wrong type."));
    }

    /**
     * Anything the database refused that nobody turned into a sentence. The
     * real error goes to the log; the caller gets a plain message — never
     * the SQL statement and constraint names, which used to come through
     * as the 500's message.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> database(DataAccessException e) {
        log.error("database error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("SERVER_ERROR", "Something went wrong on the server. "
                        + "Nothing was changed. Please try again."));
    }

    /** The last resort, same idea: log it, say something human. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> unexpected(RuntimeException e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("SERVER_ERROR", "Something went wrong on the server. "
                        + "Please try again."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException e) {
        HttpStatus s = HttpStatus.resolve(e.getStatusCode().value());
        String code = s == null ? String.valueOf(e.getStatusCode().value()) : s.name();
        String message = e.getReason() == null || e.getReason().isBlank()
                ? "The request could not be completed." : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(new ApiError(code, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("Some of the request was invalid.");
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_REQUEST", "The request could not be read."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(new ApiError("PAYLOAD_TOO_LARGE",
                        "Pictures must be 5 MB or smaller (25 MB per post in total)."));
    }
}
