package com.meowpay.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meowpay")
public record MeowPayProperties(Jwt jwt, Cors cors) {

    public record Jwt(String secret, Duration ttl) {
    }

    public record Cors(String origin) {
    }
}
