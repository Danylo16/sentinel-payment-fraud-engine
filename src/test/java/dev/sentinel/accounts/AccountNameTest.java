package dev.sentinel.accounts;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountNameTest {
    @Test void normalizesSurroundingWhitespace() {
        assertEquals("Demo", new AccountName("  Demo  ").value());
    }
    @Test void rejectsNull() { assertThrows(IllegalArgumentException.class, () -> new AccountName(null)); }
    @Test void rejectsWhitespaceOnly() { assertThrows(IllegalArgumentException.class, () -> new AccountName(" \t\n")); }
    @Test void rejectsMoreThan100Characters() {
        assertThrows(IllegalArgumentException.class, () -> new AccountName("a".repeat(101)));
    }
    @Test void accepts100UnicodeCodePoints() {
        assertEquals(100, new AccountName("🚗".repeat(100)).value().codePointCount(0, 200));
    }
    @Test void rejects101UnicodeCodePoints() {
    String name = "🚗".repeat(101);

    assertThrows(
            IllegalArgumentException.class,
            () -> new AccountName(name)
    );
}
}
