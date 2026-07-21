package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountProvisioningService accountProvisioningService;

    private AccountService accountService;

    private final Instant eventTimestamp = Instant.parse("2026-05-15T14:02:11Z");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, transactionRepository, accountProvisioningService);
    }

    @Test
    void firstApplication_createsAccountAndAppliesCreditWithPositiveSign() {
        String accountId = "acct-1";
        when(transactionRepository.findByEventId("evt-1")).thenReturn(Optional.empty());
        when(accountRepository.existsById(accountId)).thenReturn(false);
        when(accountRepository.findById(accountId))
                .thenReturn(Optional.of(accountWithBalance(accountId, new BigDecimal("150.0000"))));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionApplicationResult result = accountService.applyTransaction(
                accountId, "evt-1", TransactionType.CREDIT, new BigDecimal("150.00"), eventTimestamp);

        verify(accountProvisioningService).createAccount(eq(accountId), any(Instant.class));
        ArgumentCaptor<BigDecimal> signedAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountRepository).applySignedAmount(eq(accountId), signedAmountCaptor.capture(), any(Instant.class));
        assertThat(signedAmountCaptor.getValue()).isEqualByComparingTo("150.00");
        assertThat(result.balance()).isEqualByComparingTo("150.0000");
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void firstApplication_appliesDebitWithNegativeSign() {
        String accountId = "acct-2";
        when(transactionRepository.findByEventId("evt-2")).thenReturn(Optional.empty());
        when(accountRepository.existsById(accountId)).thenReturn(true);
        when(accountRepository.findById(accountId))
                .thenReturn(Optional.of(accountWithBalance(accountId, new BigDecimal("-40.0000"))));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        accountService.applyTransaction(accountId, "evt-2", TransactionType.DEBIT, new BigDecimal("40.00"), eventTimestamp);

        ArgumentCaptor<BigDecimal> signedAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountRepository).applySignedAmount(eq(accountId), signedAmountCaptor.capture(), any(Instant.class));
        assertThat(signedAmountCaptor.getValue()).isEqualByComparingTo("-40.00");
        // Account already existed, so it must not be (re-)created.
        verify(accountProvisioningService, never()).createAccount(any(), any());
    }

    @Test
    void idempotentReplay_returnsExistingResultWithoutReapplying() {
        String accountId = "acct-3";
        Transaction existing = new Transaction("evt-3", accountId, TransactionType.CREDIT,
                new BigDecimal("100.00"), eventTimestamp, Instant.now());
        when(transactionRepository.findByEventId("evt-3")).thenReturn(Optional.of(existing));
        when(accountRepository.findById(accountId))
                .thenReturn(Optional.of(accountWithBalance(accountId, new BigDecimal("100.0000"))));

        TransactionApplicationResult result = accountService.applyTransaction(
                accountId, "evt-3", TransactionType.CREDIT, new BigDecimal("100.00"), eventTimestamp);

        assertThat(result.transaction()).isSameAs(existing);
        assertThat(result.balance()).isEqualByComparingTo("100.0000");
        assertThat(result.replayed()).isTrue();
        verify(accountRepository, never()).applySignedAmount(any(), any(), any());
        verify(transactionRepository, never()).save(any());
        verify(accountProvisioningService, never()).createAccount(any(), any());
        verify(accountRepository, times(1)).findById(accountId);
    }

    private Account accountWithBalance(String accountId, BigDecimal balance) {
        Account account = new Account(accountId, Instant.now());
        account.setBalance(balance);
        return account;
    }
}
