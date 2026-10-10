package com.digitalwallet.wallet.wallet;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @EntityGraph(attributePaths = "transaction")
    Page<LedgerEntry> findByWalletIdOrderByIdDesc(Long walletId, Pageable pageable);

    /** Sum of credits minus debits; must always equal the wallet balance (reconciliation). */
    @Query("""
            select coalesce(sum(case when e.direction = :credit then e.amount else -e.amount end), 0)
            from LedgerEntry e where e.walletId = :walletId
            """)
    BigDecimal netBalance(@Param("walletId") Long walletId, @Param("credit") LedgerEntry.Direction credit);

    default BigDecimal netBalance(Long walletId) {
        return netBalance(walletId, LedgerEntry.Direction.CREDIT);
    }
}
