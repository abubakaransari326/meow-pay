package com.meowpay.auth;

public final class AuthDtos {

    public record AuthRequest(String username, String password) {
    }

    private AuthDtos() {
    }
}
