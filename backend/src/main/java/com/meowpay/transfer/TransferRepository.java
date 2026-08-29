package com.meowpay.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    @Query("""
            select t from Transfer t
            where t.senderId = :catId
               or (t.recipientId = :catId and t.status = com.meowpay.transfer.TransferStatus.COMPLETED)
            order by t.createdAt desc
            """)
    List<Transfer> findVisibleHistory(@Param("catId") UUID catId);
}
