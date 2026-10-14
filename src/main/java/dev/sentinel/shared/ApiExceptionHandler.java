package dev.sentinel.shared;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ApiError(Instant timestamp, int status, String code, String message, String errorId,
                           Map<String, String> fields) {}

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return error(404, "NOT_FOUND", ex.getMessage(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> unknownRoute(NoResourceFoundException ex) {
        return error(404, "NOT_FOUND", "Route not found", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMethod(HttpRequestMethodNotSupportedException ex) {
        return error(405, "METHOD_NOT_ALLOWED", "HTTP method not supported", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedContentType(HttpMediaTypeNotSupportedException ex) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Expected application/json", Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        var fields = new java.util.TreeMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
        return error(400, "VALIDATION_ERROR", "Invalid request", fields);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException ex) {
        return error(400, "VALIDATION_ERROR", "Invalid account name: expected 1 to 100 characters", Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception ex) {
        return error(400, "INVALID_REQUEST", "Malformed JSON or invalid parameter", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        String errorId = UUID.randomUUID().toString();
        // Exception messages may contain SQL parameters: log only class and correlation ID.
        log.atError().addKeyValue("errorId", errorId).addKeyValue("exceptionType", ex.getClass().getName())
                .log("Request failed unexpectedly");
        return ResponseEntity.status(500).body(new ApiError(Instant.now(), 500, "INTERNAL_ERROR",
                "Unexpected server error", errorId, Map.of()));
    }

    private static ResponseEntity<ApiError> error(int status, String code, String message, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status, code, message,
                UUID.randomUUID().toString(), fields));
    }
}
