package com.marketflow.catalog.application;

import java.util.Optional;

/**
 * Image storage boundary. The local implementation writes to a volume; a production deployment would plug in
 * an object store (S3/GCS) behind the same interface and return CDN URLs.
 */
public interface ImageStorage {

    /** Stores validated image bytes and returns the public URL path. */
    String store(byte[] bytes);

    record StoredImage(byte[] bytes, String contentType) {
    }

    Optional<StoredImage> load(String name);
}
