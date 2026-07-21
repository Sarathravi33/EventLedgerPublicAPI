package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.EventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "accountId is required")
        String accountId,

        @NotNull(message = "type is required and must be CREDIT or DEBIT")
        EventType type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp,

        Map<String, Object> metadata
) {
}
