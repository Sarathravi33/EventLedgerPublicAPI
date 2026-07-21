package com.eventledger.account.api;

import com.eventledger.account.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotNull(message = "type is required and must be CREDIT or DEBIT")
        TransactionType type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp
) {
}
