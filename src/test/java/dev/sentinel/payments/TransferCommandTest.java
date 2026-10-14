package dev.sentinel.payments;

import dev.sentinel.shared.RequestValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferCommandTest {
    @Test void rejectsSelfTransfer() {
        UUID id = UUID.randomUUID();
        assertThrows(RequestValidationException.class, () -> new TransferCommand(id, id, 1));
    }
    @Test void rejectsMissingAccounts() {
        assertThrows(RequestValidationException.class, () -> new TransferCommand(null, UUID.randomUUID(), 1));
    }
    @Test void rejectsZeroAndNegativeAmounts() {
        assertThrows(RequestValidationException.class, () -> new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 0));
        assertThrows(RequestValidationException.class, () -> new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), -1));
    }
    @Test void acceptsLargestPositiveAmountWithoutFloatingPoint() {
        assertEquals(Long.MAX_VALUE, new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE).amountCents());
    }
    @Test void validatesKeyBoundaries() {
        assertDoesNotThrow(() -> TransferCommand.validateKey("a".repeat(128)));
        assertThrows(RequestValidationException.class, () -> TransferCommand.validateKey("a".repeat(129)));
        assertThrows(RequestValidationException.class, () -> TransferCommand.validateKey("contains space"));
        assertThrows(RequestValidationException.class, () -> TransferCommand.validateKey(""));
        assertThrows(RequestValidationException.class, () -> TransferCommand.validateKey(null));
    }
    @Test void acceptsOneCentWithoutChangingAmount() {
    UUID source = UUID.randomUUID();
    UUID target = UUID.randomUUID();

    TransferCommand command = new TransferCommand(
            source,
            target,
            1L
    );

    assertEquals(1L, command.amountCents());
}
}
