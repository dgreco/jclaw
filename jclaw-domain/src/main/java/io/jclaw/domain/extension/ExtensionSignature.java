package io.jclaw.domain.extension;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Ed25519 signatures over a package digest, and the key encodings the CLI and config use.
 *
 * <p>Keys travel as base64 of their standard encodings (X.509 for public, PKCS#8 for private),
 * so a trusted publisher in {@code jclaw.trusted-publishers} is one base64 string and nothing
 * here parses ASN.1 by hand. Verification is deterministic; signing with Ed25519 is too, so both
 * sit in the domain. Generating a key pair draws randomness and lives in the adapter that does it.
 */
public final class ExtensionSignature {

    private static final String ALGORITHM = "Ed25519";

    private ExtensionSignature() {
    }

    /** Signs a digest. Returns the signature as base64. */
    public static String sign(String digestHex, String privateKeyBase64) {
        Objects.requireNonNull(digestHex, "digestHex");
        Objects.requireNonNull(privateKeyBase64, "privateKeyBase64");
        try {
            PrivateKey key = KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.trim())));
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(digestHex.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("cannot sign with the given key: " + e.getMessage());
        }
    }

    /** Whether {@code signatureBase64} is the publisher's signature over {@code digestHex}. */
    public static boolean verify(String digestHex, String signatureBase64, String publicKeyBase64) {
        Objects.requireNonNull(digestHex, "digestHex");
        Objects.requireNonNull(signatureBase64, "signatureBase64");
        Objects.requireNonNull(publicKeyBase64, "publicKeyBase64");
        try {
            PublicKey key = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(digestHex.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A malformed key or signature is a failed verification, not an error to handle.
            return false;
        }
    }
}
