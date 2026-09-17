package com.tsb.auth;

import com.tsb.auth.AuthDtos.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> switch (f.getField()) {
                    case "username" -> "Username must be 3 to 20 characters.";
                    case "email" -> "Enter a valid email address.";
                    case "password" -> "Enter your password.";
                    default -> "Please check what you entered.";
                })
                .orElse("Please check what you entered.");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("USERNAME_INVALID", message));
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
