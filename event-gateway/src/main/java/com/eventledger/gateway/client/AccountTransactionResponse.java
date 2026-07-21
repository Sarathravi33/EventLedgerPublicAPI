package com.eventledger.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Only the fields the Gateway currently needs from the Account Service's response; the nested
 * {@code transaction} object is intentionally ignored rather than mirrored here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AccountTransactionResponse(String accountId, BigDecimal balance) {
}
