package com.meowpay.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meowpay.error.ErrorCodes;
import com.meowpay.error.ErrorResponse;
import com.meowpay.transfer.SendResult;
import com.meowpay.transfer.TransferDtos;
import com.meowpay.transfer.TransferService;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransferDtos.CreateTransferRequest request
    ) {
        SendResult result = transferService.send(
                (UUID) authentication.getPrincipal(),
                request.recipientUsername(),
                request.amount(),
                idempotencyKey
        );
        return switch (result) {
            case SendResult.Completed completed -> ResponseEntity
                    .status(completed.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(completed.body());
            case SendResult.InsufficientFunds ignored -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(ErrorCodes.INSUFFICIENT_FUNDS, "Not enough treats."));
            case SendResult.IdempotencyConflict ignored -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(
                            ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "This key was already used with a different send."
                    ));
        };
    }
}
