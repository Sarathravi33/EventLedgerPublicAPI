package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The plain HTTP call, with no resiliency handling of its own — any failure is normalized to
 * {@link AccountServiceCallException} (or {@link AccountNotFoundException} for a genuine 404)
 * so callers have a single failure signal to handle regardless of cause. Deliberately does
 * <b>not</b> implement {@link AccountServiceClient} — {@link ResilientAccountServiceClient}
 * wraps this class and is the sole bean of that type, so nothing can accidentally bypass the
 * timeout/retry/circuit-breaker layer by autowiring the interface.
 */
@Component
public class RestAccountServiceClient {

    private final RestClient restClient;

    public RestAccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    public AccountServiceApplyResult applyTransaction(String accountId, String eventId, EventType type,
                                                        BigDecimal amount, Instant eventTimestamp) {
        try {
            AccountTransactionResponse response = restClient.post()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .body(new AccountTransactionRequest(eventId, type.name(), amount, eventTimestamp))
                    .retrieve()
                    .body(AccountTransactionResponse.class);
            return new AccountServiceApplyResult(response.balance());
        } catch (RestClientException e) {
            throw new AccountServiceCallException("Account Service transaction call failed: " + e.getMessage(), e);
        }
    }

    public BigDecimal getBalance(String accountId) {
        try {
            AccountBalanceResponse response = restClient.get()
                    .uri("/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .body(AccountBalanceResponse.class);
            return response.balance();
        } catch (HttpClientErrorException.NotFound e) {
            throw new AccountNotFoundException(accountId);
        } catch (RestClientException e) {
            throw new AccountServiceCallException("Account Service balance call failed: " + e.getMessage(), e);
        }
    }
}
