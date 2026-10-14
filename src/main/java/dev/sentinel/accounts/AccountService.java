package dev.sentinel.accounts;

import dev.sentinel.shared.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    public record AccountPage(java.util.List<Account> items, UUID nextBeforeId) {}
    private final AccountRepository repository;
    AccountService(AccountRepository repository) { this.repository = repository; }

    @Transactional
    public Account create(String name) { return repository.create(new AccountName(name)); }

    @Transactional(readOnly = true)
    public AccountPage page(int limit, UUID beforeId) {
        if (beforeId != null) get(beforeId);
        return repository.page(limit, beforeId);
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
