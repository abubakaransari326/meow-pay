package com.meowpay.api;

public final class MeDtos {

    public record MeResponse(String username, long balance) {
    }

    public record RecipientResponse(String username) {
    }

    private MeDtos() {
    }
}
