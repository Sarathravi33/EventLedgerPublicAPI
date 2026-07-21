package com.eventledger.gateway.client;

import java.math.BigDecimal;

record AccountBalanceResponse(String accountId, BigDecimal balance) {
}
