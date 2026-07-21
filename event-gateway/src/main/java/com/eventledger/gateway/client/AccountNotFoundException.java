package com.eventledger.gateway.client;

/** The Account Service reported (via a 404) that the account does not exist. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
