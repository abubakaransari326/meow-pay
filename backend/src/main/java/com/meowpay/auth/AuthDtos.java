package com.meowpay.auth;

public final class AuthDtos {

    public record AuthRequest(String username, String password) {
    }

    public record AuthResponse(String token, String username) {
    }

    private AuthDtos() {
    }
}
