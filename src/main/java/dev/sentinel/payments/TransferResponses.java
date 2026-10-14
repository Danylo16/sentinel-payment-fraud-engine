package dev.sentinel.payments;

import dev.sentinel.shared.ApiExceptionHandler.ApiError;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;

final class TransferResponses {
    private TransferResponses() {}
    static ResponseEntity<?> respond(TransferResult result) {
        Transfer t = result.transfer();
        var response = ResponseEntity.status(t.status().equals("REJECTED") ? 409 : result.replayed() ? 200 : 201)
                .location(URI.create("/transfers/" + t.id())).header("Idempotency-Replayed", Boolean.toString(result.replayed()));
        if (t.status().equals("REJECTED")) {
            String message = t.failureReason().equals("INSUFFICIENT_FUNDS") ? "Insufficient balance" : "Account balance limit reached";
            // Persisted timestamp and transfer ID make the rejection response identical on replay.
            return response.body(new ApiError(t.createdAt().toInstant(), 409, t.failureReason(), message,
                    t.id().toString(), Map.of("transferId", t.id().toString())));
        }
        return response.body(t);
    }
}
