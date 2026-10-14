package dev.sentinel.payments;

public record TransferResult(Transfer transfer, boolean replayed) {}
