package com.meowpay.transfer;

public sealed interface SendResult {

    record Completed(TransferDtos.TransferResponse body, boolean replay) implements SendResult {
    }

    record InsufficientFunds() implements SendResult {
    }

    record IdempotencyConflict() implements SendResult {
    }
}
