package dev.sentinel.shared;

/** Messages are developer-defined contract errors; never include a secret or SQL text. */
public class RequestValidationException extends RuntimeException {
    public RequestValidationException(String message) { super(message); }
}
