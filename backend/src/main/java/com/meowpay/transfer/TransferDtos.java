package com.meowpay.transfer;

import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    public record CreateTransferRequest(String recipientUsername, Integer amount) {
    }

    public record HistoryItem(
            UUID id,
            String counterpartyUsername,
            String direction,
            int amount,
            TransferStatus status,
            Instant createdAt
    ) {
    }

    public record TransferResponse(
            UUID id,
            String senderUsername,
            String recipientUsername,
            int amount,
            TransferStatus status,
            Instant createdAt
    ) {
    }

    private TransferDtos() {
    }
}
