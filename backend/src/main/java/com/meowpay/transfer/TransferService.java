package com.meowpay.transfer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.meowpay.cat.Cat;
import com.meowpay.cat.CatRepository;
import com.meowpay.common.Usernames;
import com.meowpay.error.ApiException;
import com.meowpay.error.ErrorCodes;
import com.meowpay.ledger.LedgerEntry;
import com.meowpay.ledger.LedgerEntryRepository;
import com.meowpay.ledger.LedgerEntryType;

@Service
public class TransferService {

    static final int MAX_KEY_LENGTH = 128;

    private final CatRepository catRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionTemplate transactionTemplate;

    public TransferService(
            CatRepository catRepository,
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.catRepository = catRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Not {@code @Transactional}. Unique-violation catch runs after the inner TX ends. */
    public SendResult send(UUID senderId, String rawRecipient, Integer amount, String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "Idempotency-Key is required.");
        }
        if (amount == null || amount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "Amount must be a positive integer.");
        }
        String recipientUsername = Usernames.lowercase(Usernames.trim(rawRecipient));
        if (recipientUsername.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "Recipient is required.");
        }
        Cat recipient = catRepository.findByUsername(recipientUsername)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "No cat with that username."));
        if (recipient.getId().equals(senderId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.SAME_CAT, "You cannot send treats to yourself.");
        }
        try {
            return transactionTemplate.execute(status -> execute(senderId, recipient, amount, key));
        } catch (DataIntegrityViolationException first) {
            return replayOrRetry(senderId, recipient, amount, key, false);
        }
    }

    public List<TransferDtos.HistoryItem> history(UUID catId) {
        List<Transfer> rows = transferRepository.findVisibleHistory(catId);
        Map<UUID, String> names = new HashMap<>();
        for (Transfer row : rows) {
            names.putIfAbsent(row.getSenderId(), null);
            names.putIfAbsent(row.getRecipientId(), null);
        }
        for (UUID id : List.copyOf(names.keySet())) {
            catRepository.findById(id).ifPresent(c -> names.put(id, c.getUsername()));
        }
        List<TransferDtos.HistoryItem> items = new ArrayList<>();
        for (Transfer row : rows) {
            boolean outbound = row.getSenderId().equals(catId);
            UUID counterpartyId = outbound ? row.getRecipientId() : row.getSenderId();
            items.add(new TransferDtos.HistoryItem(
                    row.getId(),
                    names.getOrDefault(counterpartyId, "unknown"),
                    outbound ? "OUT" : "IN",
                    row.getAmount(),
                    row.getStatus(),
                    row.getCreatedAt()
            ));
        }
        return items;
    }

    private SendResult execute(UUID senderId, Cat recipient, int amount, String key) {
        catRepository.lockById(senderId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "Please sign in."));

        var existing = transferRepository.findBySenderIdAndIdempotencyKey(senderId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), recipient.getId(), amount);
        }

        long balance = ledgerEntryRepository.sumBalance(senderId);
        Instant now = Instant.now();
        if (balance < amount) {
            transferRepository.save(new Transfer(
                    UUID.randomUUID(),
                    senderId,
                    recipient.getId(),
                    amount,
                    TransferStatus.REJECTED,
                    key,
                    now
            ));
            return new SendResult.InsufficientFunds();
        }

        Transfer transfer = transferRepository.save(new Transfer(
                UUID.randomUUID(),
                senderId,
                recipient.getId(),
                amount,
                TransferStatus.COMPLETED,
                key,
                now
        ));
        ledgerEntryRepository.save(new LedgerEntry(
                UUID.randomUUID(),
                senderId,
                -amount,
                LedgerEntryType.TRANSFER_DEBIT,
                transfer.getId(),
                now
        ));
        ledgerEntryRepository.save(new LedgerEntry(
                UUID.randomUUID(),
                recipient.getId(),
                amount,
                LedgerEntryType.TRANSFER_CREDIT,
                transfer.getId(),
                now
        ));
        return new SendResult.Completed(toResponse(transfer, senderId, recipient.getUsername()), false);
    }

    private SendResult replayOrRetry(UUID senderId, Cat recipient, int amount, String key, boolean retried) {
        var existing = transferRepository.findBySenderIdAndIdempotencyKey(senderId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), recipient.getId(), amount);
        }
        if (retried) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.IDEMPOTENCY_CONFLICT,
                    "Could not complete that send. Try again."
            );
        }
        try {
            return transactionTemplate.execute(status -> execute(senderId, recipient, amount, key));
        } catch (DataIntegrityViolationException second) {
            return replayOrRetry(senderId, recipient, amount, key, true);
        }
    }

    private SendResult replay(Transfer existing, UUID recipientId, int amount) {
        if (!existing.getRecipientId().equals(recipientId) || existing.getAmount() != amount) {
            return new SendResult.IdempotencyConflict();
        }
        if (existing.getStatus() == TransferStatus.REJECTED) {
            return new SendResult.InsufficientFunds();
        }
        Cat recipient = catRepository.findById(existing.getRecipientId()).orElseThrow();
        return new SendResult.Completed(toResponse(existing, existing.getSenderId(), recipient.getUsername()), true);
    }

    private TransferDtos.TransferResponse toResponse(Transfer transfer, UUID senderId, String recipientUsername) {
        String senderUsername = catRepository.findById(senderId).map(Cat::getUsername).orElse("unknown");
        return new TransferDtos.TransferResponse(
                transfer.getId(),
                senderUsername,
                recipientUsername,
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getCreatedAt()
        );
    }
}
