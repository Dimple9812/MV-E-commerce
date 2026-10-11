package com.marketflow.platform.security;

public enum Role {
    CUSTOMER, VENDOR, ADMIN;

    public static Role parse(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null; // unknown roles are ignored, never mapped to something privileged
        }
    }
}
