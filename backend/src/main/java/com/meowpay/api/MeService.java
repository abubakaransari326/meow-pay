package com.meowpay.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.meowpay.cat.Cat;
import com.meowpay.cat.CatRepository;
import com.meowpay.error.ApiException;
import com.meowpay.error.ErrorCodes;
import com.meowpay.ledger.LedgerEntryRepository;

@Service
public class MeService {

    private final CatRepository catRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public MeService(CatRepository catRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.catRepository = catRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public MeDtos.MeResponse me(UUID catId) {
        Cat cat = catRepository.findById(catId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCodes.UNAUTHORIZED,
                        "Please sign in."
                ));
        return new MeDtos.MeResponse(cat.getUsername(), ledgerEntryRepository.sumBalance(catId));
    }

    public List<MeDtos.RecipientResponse> recipients(UUID catId) {
        return catRepository.findByIdNotOrderByUsernameAsc(catId).stream()
                .map(c -> new MeDtos.RecipientResponse(c.getUsername()))
                .toList();
    }
}
