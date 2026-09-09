package io.jclaw.domain.extension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A stable digest of a package's contents: what a publisher signs and what an installation
 * records.
 *
 * <p>SHA-256 over every file, in path order, each framed as its path, a separator, its length,
 * and its bytes, so that renaming a file, moving bytes between files, or appending one changes
 * the digest. The signature file itself is excluded by the caller. Pure: it takes bytes, not a
 * directory.
 */
public final class PackageDigest {

    private PackageDigest() {
    }

    /** Hex SHA-256 of the files, keyed by their relative path with {@code /} separators. */
    public static String of(Map<String, byte[]> files) {
        Objects.requireNonNull(files, "files");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(files).entrySet()) {
            byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
            sha.update(path);
            sha.update((byte) 0);
            sha.update(Long.toString(entry.getValue().length).getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(entry.getValue());
            sha.update((byte) '\n');
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
