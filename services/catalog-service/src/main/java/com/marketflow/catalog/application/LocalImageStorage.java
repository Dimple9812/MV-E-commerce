package com.marketflow.catalog.application;

import com.marketflow.platform.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores uploads on a local volume. The file type is decided by magic bytes, never by the client-supplied name
 * or Content-Type; file names are random UUIDs, and lookups only accept that exact pattern (no path traversal).
 */
@Component
public class LocalImageStorage implements ImageStorage {

    private static final Pattern NAME = Pattern.compile("^[0-9a-f\\-]{36}\\.(png|jpg|webp)$");
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private final Path dir;
    private final String publicPrefix;

    public LocalImageStorage(@Value("${marketflow.catalog.image-dir:./data/images}") String dir,
                             @Value("${marketflow.catalog.image-url-prefix:/api/v1/products/images/}") String prefix) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.publicPrefix = prefix;
    }

    @Override
    public String store(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw ApiException.badRequest("invalid_image", "Image must be between 1 byte and 2 MB");
        }
        String ext = detect(bytes);
        if (ext == null) {
            throw ApiException.badRequest("invalid_image", "Only PNG, JPEG and WebP images are accepted");
        }
        String name = UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return publicPrefix + name;
    }

    @Override
    public Optional<StoredImage> load(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
            return Optional.of(new StoredImage(Files.readAllBytes(file), type));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String detect(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
