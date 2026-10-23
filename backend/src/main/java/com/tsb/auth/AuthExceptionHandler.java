package com.tsb.auth;

import com.tsb.auth.AuthDtos.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns auth failures into the {@code {code, message, retryAfterSeconds}}
 * shape the frontend switches on.
 *
 * <p>Scoped to {@code assignableTypes = AuthController.class} so it cannot
 * quietly change how the rest of the API reports errors.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) // before the general ApiExceptionHandler
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ErrorResponse> handle(AuthException e) {
        var body = new ErrorResponse(e.getCode(), e.getMessage(), e.getRetryAfterSeconds());
        var response = ResponseEntity.status(e.getStatus());
        if (e.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    /**
     * Bean-validation failures. The first message is enough — showing four
     * at once on a sign-up form is noise, and the client validates the same
     * rules before submitting anyway.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException e) {
        var first = e.getBindingResult().getFieldErrors().stream().findFirst();
        String field = first.map(f -> f.getField()).orElse("");
        // A code per field, so the form marks the right box.
        String code = switch (field) {
            case "username" -> "USERNAME_INVALID";
            case "password" -> "WEAK_PASSWORD";
            default -> "VALIDATION";
        };
        String message = switch (field) {
            case "username" -> "Username must be 3 to 20 characters.";
            case "email" -> "Enter a valid email address.";
            case "password" -> first.map(f -> f.getRejectedValue() == null
                    || String.valueOf(f.getRejectedValue()).isEmpty()
                    ? "Enter your password." : "That password is too long.")
                    .orElse("Enter your password.");
            default -> "Please check what you entered.";
        };
        return ResponseEntity.badRequest().body(ErrorResponse.of(code, message));
    }

    /** Malformed JSON, a wrong content type, a missing parameter: the
     *  caller's mistake, so a 400 — not a 500 with a stack trace in the log. */
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", "The request could not be read."));
    }

    /**
     * The catch-all. Logs the real cause and returns nothing useful to the
     * caller — a stack trace on a login endpoint is a gift to whoever is
     * probing it.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handle(Exception e) {
        log.error("Unhandled error in auth endpoint", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("UNKNOWN", "Something went wrong. Please try again."));
    }
}
