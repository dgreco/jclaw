package io.jclaw.storage.extension;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Generates a publisher key pair. The one place in extension signing that draws randomness. */
public final class PublisherKeys {

    /** Base64 encodings: X.509 for the public key, PKCS#8 for the private one. */
    public record Pair(String publicKey, String privateKey) {
    }

    private PublisherKeys() {
    }

    public static Pair generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new Pair(
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 is mandatory since JDK 15", e);
        }
    }
}
