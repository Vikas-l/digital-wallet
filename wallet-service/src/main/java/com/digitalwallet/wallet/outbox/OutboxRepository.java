package com.digitalwallet.wallet.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * FOR UPDATE SKIP LOCKED lets several service instances drain the outbox in
     * parallel without ever picking up the same row twice.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);

    long countByStatus(OutboxEvent.Status status);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = :status and e.publishedAt < :before")
    int deletePublishedBefore(@Param("status") OutboxEvent.Status status, @Param("before") Instant before);
}
