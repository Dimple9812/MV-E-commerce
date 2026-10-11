package com.marketflow.identity.application;

import com.marketflow.platform.web.ApiException;

/** Minimum 10 characters with at least one letter and one digit; maximum 72 bytes (BCrypt input limit). */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < 10) {
            throw weak("Password must be at least 10 characters");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw weak("Password must be at most 72 bytes");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw weak("Password must contain letters and digits");
        }
    }

    private static ApiException weak(String msg) {
        return ApiException.badRequest("weak_password", msg);
    }
}
