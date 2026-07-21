package com.eventledger.account.repository;

import com.eventledger.account.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    List<Transaction> findByAccountIdOrderByEventTimestampAsc(String accountId);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) "
            + "FROM Transaction t WHERE t.accountId = :accountId")
    BigDecimal computeBalance(@Param("accountId") String accountId);
}
