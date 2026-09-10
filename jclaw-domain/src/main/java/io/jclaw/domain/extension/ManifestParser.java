// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.extension;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.extension.ExtensionRegistry.Kind;
import io.jclaw.contracts.extension.ExtensionRegistry.Manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a decoded {@code jclaw-extension.json} into a {@link Manifest}, or says why not.
 *
 * <p>Takes the already-decoded map so the domain stays free of any JSON library; the adapter
 * that reads the file decodes it. Every field is checked here rather than trusted: a manifest is
 * third-party input.
 */
public final class ManifestParser {

    private ManifestParser() {
    }

    public static Result<Manifest, String> parse(Map<String, Object> raw) {
        Objects.requireNonNull(raw, "raw");
        String name = text(raw, "name");
        String version = text(raw, "version");
        String kindText = text(raw, "kind").toUpperCase(Locale.ROOT);
        if (name.isEmpty() || version.isEmpty() || kindText.isEmpty()) {
            return Result.err("manifest_requires_name_version_kind");
        }
        Kind kind;
        try {
            kind = Kind.valueOf(kindText);
        } catch (IllegalArgumentException e) {
            return Result.err("manifest_kind_must_be_skill_mcp_or_wasm");
        }
        EffectClass effect = EffectClass.NETWORK;
        String effectText = text(raw, "effect");
        if (!effectText.isEmpty()) {
            try {
                effect = EffectClass.valueOf(effectText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Result.err("manifest_effect_unknown");
            }
        }
        String publisher = text(raw, "publisher");
        try {
            return Result.ok(new Manifest(
                    name, version, text(raw, "description"), kind,
                    strings(raw, "command"), strings(raw, "env"), strings(raw, "hosts"),
                    strings(raw, "permissions"), strings(raw, "tools"), effect,
                    publisher.isEmpty() ? Optional.empty() : Optional.of(publisher)));
        } catch (IllegalArgumentException e) {
            return Result.err("manifest_invalid: " + e.getMessage());
        }
    }

    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value instanceof String s ? s.trim() : "";
    }

    private static List<String> strings(Map<String, Object> raw, String key) {
        List<String> out = new ArrayList<>();
        if (raw.get(key) instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
