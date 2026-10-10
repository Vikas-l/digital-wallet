package com.digitalwallet.wallet.wallet;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * Returns only the id so that no Wallet entity enters the persistence context
     * before it is locked (a pre-loaded entity would not be refreshed by the lock query).
     */
    @Query("select w.id from Wallet w where w.userId = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    /** SELECT ... FOR UPDATE: the row stays locked until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
