package com.meowpay.common;

import java.util.Locale;

public final class Usernames {

    public static final int MAX_LENGTH = 64;

    private Usernames() {
    }

    public static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static String lowercase(String trimmed) {
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
