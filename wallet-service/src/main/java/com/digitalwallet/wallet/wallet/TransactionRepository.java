package com.digitalwallet.wallet.wallet;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Optional<WalletTransaction> findByInitiatorUserIdAndIdempotencyKey(Long initiatorUserId, String idempotencyKey);

    boolean existsBySingleUseQrId(UUID singleUseQrId);
}
