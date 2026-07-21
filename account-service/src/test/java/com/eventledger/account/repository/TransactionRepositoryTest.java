package com.eventledger.account.repository;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class TransactionRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void enforcesUniqueEventId() {
        Instant now = Instant.now();
        accountRepository.save(new Account("acct-1", now));
        transactionRepository.saveAndFlush(
                new Transaction("evt-dup", "acct-1", TransactionType.CREDIT, BigDecimal.TEN, now, now));

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(
                new Transaction("evt-dup", "acct-1", TransactionType.DEBIT, BigDecimal.ONE, now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void computesBalanceCorrectlyRegardlessOfInsertionOrder() {
        Instant base = Instant.now();
        accountRepository.save(new Account("acct-2", base));

        // Business events: net = +100 +50 -30 +10 -5 = 125, but they are inserted in a
        // scrambled (non-chronological, non-value-ordered) sequence to prove the aggregate
        // computation does not depend on arrival order.
        List<Transaction> transactions = new ArrayList<>(List.of(
                new Transaction("evt-a", "acct-2", TransactionType.CREDIT, new BigDecimal("100"), base, base),
                new Transaction("evt-b", "acct-2", TransactionType.DEBIT, new BigDecimal("30"), base.plus(1, ChronoUnit.SECONDS), base),
                new Transaction("evt-c", "acct-2", TransactionType.CREDIT, new BigDecimal("50"), base.minus(5, ChronoUnit.SECONDS), base),
                new Transaction("evt-d", "acct-2", TransactionType.DEBIT, new BigDecimal("5"), base.plus(2, ChronoUnit.SECONDS), base),
                new Transaction("evt-e", "acct-2", TransactionType.CREDIT, new BigDecimal("10"), base.minus(1, ChronoUnit.SECONDS), base)
        ));
        Collections.shuffle(transactions);

        transactions.forEach(transactionRepository::saveAndFlush);

        BigDecimal balance = transactionRepository.computeBalance("acct-2");

        assertThat(balance).isEqualByComparingTo("125");
    }

    @Test
    void ordersEventsChronologicallyRegardlessOfInsertionOrder() {
        Instant base = Instant.now();
        accountRepository.save(new Account("acct-3", base));

        Transaction earliest = new Transaction("evt-1", "acct-3", TransactionType.CREDIT, BigDecimal.ONE,
                base.minus(10, ChronoUnit.SECONDS), base);
        Transaction middle = new Transaction("evt-2", "acct-3", TransactionType.CREDIT, BigDecimal.ONE,
                base.minus(5, ChronoUnit.SECONDS), base);
        Transaction latest = new Transaction("evt-3", "acct-3", TransactionType.CREDIT, BigDecimal.ONE,
                base, base);

        // Insert out of chronological order.
        transactionRepository.saveAndFlush(latest);
        transactionRepository.saveAndFlush(earliest);
        transactionRepository.saveAndFlush(middle);

        List<Transaction> ordered = transactionRepository.findByAccountIdOrderByEventTimestampAsc("acct-3");

        assertThat(ordered).extracting(Transaction::getEventId)
                .containsExactly("evt-1", "evt-2", "evt-3");
    }
}
