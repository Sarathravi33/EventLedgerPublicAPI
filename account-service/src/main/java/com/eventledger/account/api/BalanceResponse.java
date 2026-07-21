package com.eventledger.account.api;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance) {
}
