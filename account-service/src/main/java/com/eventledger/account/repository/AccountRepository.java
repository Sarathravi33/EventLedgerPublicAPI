package com.eventledger.account.repository;

import com.eventledger.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface AccountRepository extends JpaRepository<Account, String> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Account a SET a.balance = a.balance + :signedAmount, a.updatedAt = :now WHERE a.accountId = :accountId")
    int applySignedAmount(@Param("accountId") String accountId,
                           @Param("signedAmount") BigDecimal signedAmount,
                           @Param("now") Instant now);
}
