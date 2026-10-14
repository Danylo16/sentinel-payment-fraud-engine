package dev.sentinel.accounts;

/** The same validation is used by HTTP-independent account creation. */
public record AccountName(String value) {
    public AccountName {
        if (value == null) throw new IllegalArgumentException("Account name is required");
        value = value.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 100) {
            throw new IllegalArgumentException("Account name must contain 1 to 100 characters");
        }
    }
}
