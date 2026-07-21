package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Runs account creation in its own transaction so that a lost race against a concurrent
 * creation attempt (two threads applying the first-ever transaction for the same account at
 * the same time) only rolls back this small sub-transaction, not the caller's.
 */
@Service
public class AccountProvisioningService {

    private final AccountRepository accountRepository;

    public AccountProvisioningService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createAccount(String accountId, Instant now) {
        accountRepository.save(new Account(accountId, now));
        accountRepository.flush();
    }
}
