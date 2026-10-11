package com.marketflow.catalog.cache;

import java.util.UUID;

/** Versioned key scheme: bump the "v1" segment when the cached JSON shape changes. */
public final class CacheKeys {

    public static final String CATEGORIES = "catalog:v1:categories";

    private CacheKeys() {
    }

    public static String product(UUID id) {
        return "catalog:v1:product:" + id;
    }
}
