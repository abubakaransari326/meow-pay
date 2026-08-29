package com.meowpay.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meowpay.transfer.TransferDtos;
import com.meowpay.transfer.TransferService;

@RestController
@RequestMapping("/api")
public class MeController {

    private final MeService meService;
    private final TransferService transferService;

    public MeController(MeService meService, TransferService transferService) {
        this.meService = meService;
        this.transferService = transferService;
    }

    @GetMapping("/me")
    public MeDtos.MeResponse me(Authentication authentication) {
        return meService.me((UUID) authentication.getPrincipal());
    }

    @GetMapping("/recipients")
    public List<MeDtos.RecipientResponse> recipients(Authentication authentication) {
        return meService.recipients((UUID) authentication.getPrincipal());
    }

    @GetMapping("/me/transfers")
    public List<TransferDtos.HistoryItem> history(Authentication authentication) {
        return transferService.history((UUID) authentication.getPrincipal());
    }
}
