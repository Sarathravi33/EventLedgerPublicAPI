package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountProvisioningService accountProvisioningService;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                           AccountProvisioningService accountProvisioningService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.accountProvisioningService = accountProvisioningService;
    }

    /**
     * Idempotent on {@code eventId}: a replay of an already-applied event returns the
     * original result unchanged instead of re-applying the amount, which is what makes it
     * safe for the Gateway to retry this call after a timeout.
     */
    @Transactional
    public TransactionApplicationResult applyTransaction(String accountId, String eventId, TransactionType type,
                                                           BigDecimal amount, Instant eventTimestamp) {
        return transactionRepository.findByEventId(eventId)
                .map(this::toExistingResult)
                .orElseGet(() -> applyNewTransaction(accountId, eventId, type, amount, eventTimestamp));
    }

    private TransactionApplicationResult toExistingResult(Transaction existing) {
        Account account = getAccountOrThrow(existing.getAccountId());
        return new TransactionApplicationResult(account.getAccountId(), account.getBalance(), existing);
    }

    private TransactionApplicationResult applyNewTransaction(String accountId, String eventId, TransactionType type,
                                                               BigDecimal amount, Instant eventTimestamp) {
        Instant now = Instant.now();
        ensureAccountExists(accountId, now);

        BigDecimal signedAmount = type == TransactionType.CREDIT ? amount : amount.negate();
        accountRepository.applySignedAmount(accountId, signedAmount, now);

        Transaction transaction = transactionRepository.save(
                new Transaction(eventId, accountId, type, amount, eventTimestamp, now));

        Account updated = getAccountOrThrow(accountId);
        return new TransactionApplicationResult(accountId, updated.getBalance(), transaction);
    }

    private void ensureAccountExists(String accountId, Instant now) {
        if (accountRepository.existsById(accountId)) {
            return;
        }
        try {
            accountProvisioningService.createAccount(accountId, now);
        } catch (DataIntegrityViolationException e) {
            // Lost the race to create the account against a concurrent first-transaction on
            // the same account; the other transaction's insert committed first, so the
            // account exists now either way.
        }
    }

    public BigDecimal getBalance(String accountId) {
        return getAccountOrThrow(accountId).getBalance();
    }

    public Account getAccountOrThrow(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
