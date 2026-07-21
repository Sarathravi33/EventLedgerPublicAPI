package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * No resiliency handling here by design (timeout/retry/circuit-breaker wrapping is added
 * around this client in a later step) — this is the plain happy-path HTTP call, with any
 * failure normalized to {@link AccountServiceCallException} so {@code EventService} has a
 * single failure signal to handle regardless of cause (timeout, connection refused, 5xx, ...).
 */
@Component
public class RestAccountServiceClient implements AccountServiceClient {

    private final RestClient restClient;

    public RestAccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    @Override
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

    @Override
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
