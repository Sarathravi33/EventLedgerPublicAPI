package com.eventledger.account.service;

import com.eventledger.account.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AccountServiceConcurrencyTest {

    @Autowired
    private AccountService accountService;

    /**
     * Proves that the atomic {@code balance = balance + :signedAmount} update (rather than a
     * read-modify-write in application code) avoids lost updates when two threads apply
     * different events to the same account concurrently.
     */
    @Test
    void concurrentApplicationsToSameAccountProduceCorrectFinalBalance() throws InterruptedException {
        String accountId = "acct-concurrent";
        int threadCount = 2;
        int eventsPerThread = 25;
        BigDecimal amountPerEvent = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger eventCounter = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        String eventId = "evt-concurrent-" + eventCounter.incrementAndGet();
                        accountService.applyTransaction(accountId, eventId, TransactionType.CREDIT,
                                amountPerEvent, Instant.now());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finished).isTrue();

        BigDecimal expected = amountPerEvent.multiply(BigDecimal.valueOf((long) threadCount * eventsPerThread));
        assertThat(accountService.getBalance(accountId)).isEqualByComparingTo(expected);
    }
}
