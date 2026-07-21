package com.eventledger.gateway.api;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance) {
}
