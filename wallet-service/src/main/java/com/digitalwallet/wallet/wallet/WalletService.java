package com.digitalwallet.wallet.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.digitalwallet.common.events.RewardGrantedEvent;
import com.digitalwallet.common.events.Topics;
import com.digitalwallet.common.events.TransactionCompletedEvent;
import com.digitalwallet.wallet.auth.AuthService;
import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.outbox.OutboxWriter;
import com.digitalwallet.wallet.user.AppUser;
import com.digitalwallet.wallet.user.UserRepository;
import com.digitalwallet.wallet.wallet.LedgerEntry.Direction;
import com.digitalwallet.wallet.wallet.WalletDtos.StatementEntry;
import com.digitalwallet.wallet.wallet.WalletDtos.WalletResponse;
import com.digitalwallet.wallet.web.PageResponse;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All balance changes go through here. Each operation runs in one DB transaction
 * that locks the affected wallet rows, updates balances, writes the transaction and
 * its ledger entries, and appends the outbox event.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;
    private final OutboxWriter outboxWriter;
    private final String currency;

    public WalletService(WalletRepository walletRepository, TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository, UserRepository userRepository,
            OutboxWriter outboxWriter, WalletProperties properties) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
        this.outboxWriter = outboxWriter;
        this.currency = properties.currency();
    }

    @Transactional
    public Wallet openWallet(Long userId) {
        return walletRepository.save(new Wallet(userId, currency));
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(Long userId) {
        return WalletResponse.of(walletRepository.findByUserId(userId).orElseThrow(WalletService::walletNotFound));
    }

    @Transactional(readOnly = true)
    public PageResponse<StatementEntry> statement(Long userId, Pageable pageable) {
        Long walletId = walletRepository.findIdByUserId(userId).orElseThrow(WalletService::walletNotFound);
        return PageResponse.of(ledgerEntryRepository.findByWalletIdOrderByIdDesc(walletId, pageable)
                .map(StatementEntry::of));
    }

    @Transactional
    public WalletTransaction topUp(Long userId, BigDecimal amount, String idempotencyKey, String requestHash) {
        BigDecimal value = money(amount);
        Long walletId = walletRepository.findIdByUserId(userId).orElseThrow(WalletService::walletNotFound);
        Wallet wallet = lock(walletId);

        wallet.credit(value);
        WalletTransaction tx = transactionRepository.save(
                WalletTransaction.topUp(userId, wallet, value, idempotencyKey, requestHash));
        ledgerEntryRepository.save(new LedgerEntry(tx, wallet, Direction.CREDIT));
        publishCompleted(tx, userId);
        return tx;
    }

    @Transactional
    public WalletTransaction transfer(Long userId, String recipientEmail, BigDecimal amount, String note,
            String idempotencyKey, String requestHash) {
        AppUser recipient = userRepository.findByEmail(AuthService.normalize(recipientEmail))
                .orElseThrow(() -> ApiException.notFound("RECIPIENT_NOT_FOUND", "No user with that email"));
        String description = note == null || note.isBlank() ? "Transfer to " + recipient.getEmail() : note.trim();
        return moveMoney(userId, recipient, amount, description, idempotencyKey, requestHash, null);
    }

    /**
     * Pays a scanned QR code. Same locking, ledger and event flow as a normal transfer
     * (so cashback rules apply too); a single-use code is additionally linked to the
     * transaction, and the unique constraint on that link makes double payment impossible.
     */
    @Transactional
    public WalletTransaction payQr(Long userId, Long payeeUserId, BigDecimal amount, String note,
            UUID singleUseQrId, String idempotencyKey, String requestHash) {
        if (singleUseQrId != null && transactionRepository.existsBySingleUseQrId(singleUseQrId)) {
            throw ApiException.conflict("QR_ALREADY_USED", "This QR code has already been paid");
        }
        AppUser payee = userRepository.findById(payeeUserId)
                .orElseThrow(() -> ApiException.notFound("RECIPIENT_NOT_FOUND", "The QR code's owner no longer exists"));
        String description = note == null || note.isBlank() ? "QR payment to " + payee.getFullName() : note.trim();
        return moveMoney(userId, payee, amount, description, idempotencyKey, requestHash, singleUseQrId);
    }

    private WalletTransaction moveMoney(Long userId, AppUser recipient, BigDecimal amount, String description,
            String idempotencyKey, String requestHash, UUID singleUseQrId) {
        BigDecimal value = money(amount);
        Long sourceId = walletRepository.findIdByUserId(userId).orElseThrow(WalletService::walletNotFound);
        Long targetId = walletRepository.findIdByUserId(recipient.getId())
                .orElseThrow(WalletService::walletNotFound);
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("SELF_TRANSFER", "You cannot transfer money to yourself");
        }

        // Always lock in ascending id order: A->B and B->A running together can then never deadlock.
        Wallet first = lock(Math.min(sourceId, targetId));
        Wallet second = lock(Math.max(sourceId, targetId));
        Wallet source = first.getId().equals(sourceId) ? first : second;
        Wallet target = source == first ? second : first;

        if (!source.getCurrency().equals(target.getCurrency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Cross-currency transfers are not supported");
        }
        source.debit(value);
        target.credit(value);

        WalletTransaction tx = transactionRepository.save(WalletTransaction.transfer(
                userId, source, target, value, description, idempotencyKey, requestHash)
                .paidWithSingleUseQr(singleUseQrId));
        ledgerEntryRepository.save(new LedgerEntry(tx, source, Direction.DEBIT));
        ledgerEntryRepository.save(new LedgerEntry(tx, target, Direction.CREDIT));
        publishCompleted(tx, userId);
        return tx;
    }

    /**
     * Credits cashback. Deliberately does NOT publish TransactionCompleted, otherwise
     * a reward could itself earn a reward (an infinite event loop).
     */
    @Transactional
    public void creditReward(RewardGrantedEvent event) {
        Long walletId = walletRepository.findIdByUserId(event.userId()).orElseThrow(WalletService::walletNotFound);
        Wallet wallet = lock(walletId);
        BigDecimal value = money(event.amount());
        wallet.credit(value);
        WalletTransaction tx = transactionRepository.save(WalletTransaction.reward(
                wallet, value, "Cashback (" + event.ruleCode() + ")", event.rewardId().toString()));
        ledgerEntryRepository.save(new LedgerEntry(tx, wallet, Direction.CREDIT));
    }

    private void publishCompleted(WalletTransaction tx, Long initiatorUserId) {
        UUID eventId = UUID.randomUUID();
        TransactionCompletedEvent event = new TransactionCompletedEvent(eventId, tx.getId(), tx.getType(),
                initiatorUserId, tx.getAmount(), tx.getCurrency(), Instant.now());
        // keyed by user: all of one user's events land on one partition, in order
        outboxWriter.append(eventId, "Transaction", tx.getId().toString(),
                Topics.TRANSACTION_COMPLETED, String.valueOf(initiatorUserId), event);
    }

    private Wallet lock(Long walletId) {
        return walletRepository.findByIdForUpdate(walletId).orElseThrow(WalletService::walletNotFound);
    }

    private static BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must have at most 2 decimal places");
        }
    }

    private static ApiException walletNotFound() {
        return ApiException.notFound("WALLET_NOT_FOUND", "Wallet not found");
    }
}
