package com.meowpay.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.meowpay.config.MeowPayProperties;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;
    private final MeowPayProperties properties;

    public JwtService(MeowPayProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(UUID catId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(catId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().ttl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public UUID parseCatId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return UUID.fromString(subject);
    }
}
