package com.meowpay.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import com.meowpay.security.JwtService;

@Service
public class AuthService {

    static final long SIGNUP_BONUS = 100;
    static final int MAX_PASSWORD_LENGTH = 72;

    private final CatRepository catRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;

    public AuthService(
            CatRepository catRepository,
            LedgerEntryRepository ledgerEntryRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            PlatformTransactionManager transactionManager
    ) {
        this.catRepository = catRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Not {@code @Transactional}. Unique-violation catch runs after the inner TX ends. */
    public AuthDtos.AuthResponse register(String rawUsername, String password) {
        String username = normalizedUsername(rawUsername, password);
        if (catRepository.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.USERNAME_TAKEN, "That username is taken.");
        }
        try {
            return transactionTemplate.execute(status -> createCatWithBonus(username, password));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.USERNAME_TAKEN, "That username is taken.");
        }
    }

    private AuthDtos.AuthResponse createCatWithBonus(String username, String password) {
        Instant now = Instant.now();
        Cat cat = catRepository.save(new Cat(
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(password),
                now
        ));
        ledgerEntryRepository.save(new LedgerEntry(
                UUID.randomUUID(),
                cat.getId(),
                SIGNUP_BONUS,
                LedgerEntryType.SIGNUP_BONUS,
                null,
                now
        ));
        return new AuthDtos.AuthResponse(jwtService.issue(cat.getId()), cat.getUsername());
    }

    public AuthDtos.AuthResponse login(String rawUsername, String password) {
        String username = normalizedUsername(rawUsername, password);
        Cat cat = catRepository.findByUsername(username)
                .filter(c -> passwordEncoder.matches(password, c.getPasswordHash()))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCodes.UNAUTHORIZED,
                        "Wrong username or password."
                ));
        return new AuthDtos.AuthResponse(jwtService.issue(cat.getId()), cat.getUsername());
    }

    private static String normalizedUsername(String rawUsername, String password) {
        String trimmed = Usernames.trim(rawUsername);
        if (trimmed.isEmpty() || trimmed.length() > Usernames.MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "Invalid username.");
        }
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "Invalid password.");
        }
        return Usernames.lowercase(trimmed);
    }
}
