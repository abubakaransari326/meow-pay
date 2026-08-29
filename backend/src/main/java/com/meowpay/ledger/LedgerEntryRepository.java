package com.meowpay.ledger;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.catId = :catId")
    long sumBalance(@Param("catId") UUID catId);
}
