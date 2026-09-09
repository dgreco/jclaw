package io.jclaw.storage.secret;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.storage.rows.RowStore;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Secrets encrypted at rest in an append-only JSONL file.
 *
 * <p>Each value is sealed with AES-256-GCM under a key the vault is given, with a fresh random
 * nonce per write and the secret's name as associated data, so a ciphertext moved to another
 * row does not decrypt under the wrong name. The file holds names, bindings, nonces, and
 * ciphertext: an operator reading it learns which secrets exist and where they may go, never
 * what they are. Removal appends a tombstone; the last row for a name wins.
 *
 * <p>The key is not this class's business. {@link VaultKey} derives or generates one for the
 * application; a test hands one in.
 */
public final class FileSecretVault implements SecretVault {

    private static final String KIND_PUT = "put";
    private static final String KIND_REMOVED = "removed";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final RowStore file;
    private final SecretKeySpec key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public FileSecretVault(RowStore file, byte[] key, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        Objects.requireNonNull(key, "key");
        if (key.length != 32) {
            throw new IllegalArgumentException("the vault key must be 32 bytes, got " + key.length);
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void put(SecretName name, String value, Binding binding) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(binding, "binding");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a secret value must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(name.value().getBytes(StandardCharsets.UTF_8));
            sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("vault encryption failed", e);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_PUT);
        row.put("name", name.value());
        row.put("capability", binding.capability().value());
        row.put("hosts", binding.hosts().stream().sorted().toList());
        row.put("createdAt", clock.instant().toString());
        row.put("nonce", Base64.getEncoder().encodeToString(nonce));
        row.put("ciphertext", Base64.getEncoder().encodeToString(sealed));
        file.append(row);
    }

    @Override
    public synchronized boolean remove(SecretName name) {
        Objects.requireNonNull(name, "name");
        if (!replay().containsKey(name.value())) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_REMOVED);
        row.put("name", name.value());
        row.put("at", clock.instant().toString());
        file.append(row);
        return true;
    }

    @Override
    public synchronized List<SecretInfo> list() {
        return replay().values().stream()
                .map(FileSecretVault::info)
                .sorted(Comparator.comparing(info -> info.name().value()))
                .toList();
    }

    @Override
    public synchronized Optional<Lease> lease(SecretName name) {
        Objects.requireNonNull(name, "name");
        Map<String, Object> row = replay().get(name.value());
        if (row == null) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(String.valueOf(row.get("nonce")))));
            cipher.updateAAD(name.value().getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(String.valueOf(row.get("ciphertext"))));
            return Optional.of(new Lease(info(row), new String(plain, StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A wrong key or a tampered row: the secret is unusable, which is the safe outcome.
            throw new IllegalStateException("vault entry '" + name.value() + "' cannot be opened with this key");
        }
    }

    private Map<String, Map<String, Object>> replay() {
        Map<String, Map<String, Object>> live = new LinkedHashMap<>();
        for (Map<String, Object> row : file.readAll()) {
            String name = String.valueOf(row.get("name"));
            switch (String.valueOf(row.get("kind"))) {
                case KIND_PUT -> live.put(name, row);
                case KIND_REMOVED -> live.remove(name);
                default -> { }
            }
        }
        return live;
    }

    @SuppressWarnings("unchecked")
    private static SecretInfo info(Map<String, Object> row) {
        List<String> hosts = new ArrayList<>();
        if (row.get("hosts") instanceof List<?> list) {
            list.forEach(host -> hosts.add(String.valueOf(host)));
        }
        return new SecretInfo(
                new SecretName(String.valueOf(row.get("name"))),
                new Binding(CapabilityId.of(String.valueOf(row.get("capability"))), Set.copyOf(hosts)),
                Instant.parse(String.valueOf(row.get("createdAt"))));
    }
}
