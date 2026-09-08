package io.jclaw.contracts.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Translates capability ids to and from the tool names model APIs accept.
 *
 * <p>jclaw namespaces capabilities with dots — {@code builtin.read_file},
 * {@code mcp.github.create_issue} — because the namespace is what stops a third-party tool from
 * impersonating a built-in. Model APIs do not allow dots: both the OpenAI-compatible and Anthropic
 * function-name schemas require {@code ^[a-zA-Z0-9_-]{1,64}$}. Sending a dotted name gets the whole
 * request rejected with a 400, which surfaces as an unhelpful failure several layers up.
 *
 * <p>So the dot becomes a single underscore on the wire — {@code builtin_read_file}. A double
 * underscore ({@code builtin__read_file}) would also be legal, but it measurably breaks small
 * local models: qwen2.5 and qwen3 served by Ollama call {@code builtin_glob} reliably and
 * {@code builtin__glob} essentially never — they emit a malformed or "corrected" call that the
 * server drops silently, which surfaces as an empty reply. Natural-looking names are not
 * cosmetic; they are what tool-tuned models were trained on.
 *
 * <p>The mapping back is a <b>lookup against the tools actually sent</b>, never a parse: a
 * capability id may itself contain underscores, so {@code x.a_b} and {@code x.a.b} both encode to
 * {@code x_a_b}. Parsing could not tell them apart; a lookup table can, and {@link #wireNamesFor}
 * refuses to build one that is ambiguous.
 */
public final class ToolNames {

    /** The intersection of what OpenAI-compatible and Anthropic APIs accept. */
    private static final Pattern WIRE_SAFE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static final int MAX_LENGTH = 64;

    /** Room for a short hash when a name has to be shortened. */
    private static final int HASH_LENGTH = 8;

    private ToolNames() {
    }

    /**
     * Encodes one capability id for the wire.
     *
     * <p>Names longer than 64 characters are truncated and given a hash suffix derived from the
     * full id, so two long ids sharing a prefix do not collapse into the same name.
     */
    public static String toWire(String capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");

        String encoded = capabilityId.replace(".", "_");
        // Anything still outside the allowed set becomes an underscore. Should not happen for a
        // valid CapabilityId, but an MCP server can supply surprising names.
        encoded = encoded.replaceAll("[^a-zA-Z0-9_-]", "_");

        if (encoded.length() > MAX_LENGTH) {
            String keep = encoded.substring(0, MAX_LENGTH - HASH_LENGTH - 1);
            encoded = keep + "_" + shortHash(capabilityId);
        }
        if (!WIRE_SAFE.matcher(encoded).matches()) {
            throw new IllegalArgumentException(
                    "cannot encode capability id as a tool name: " + capabilityId);
        }
        return encoded;
    }

    /**
     * Builds the wire-name mapping for a set of tools.
     *
     * @return wire name to capability id, in the order given
     * @throws IllegalArgumentException when two capability ids encode to the same wire name.
     *         Failing here is deliberate: a silent collision would let one capability shadow
     *         another, and a shadowed capability is an authorization hole rather than a display
     *         glitch.
     */
    public static Map<String, String> wireNamesFor(List<String> capabilityIds) {
        Objects.requireNonNull(capabilityIds, "capabilityIds");

        Map<String, String> byWireName = new LinkedHashMap<>();
        for (String id : capabilityIds) {
            String wire = toWire(id);
            String previous = byWireName.put(wire, id);
            if (previous != null && !previous.equals(id)) {
                throw new IllegalArgumentException(
                        "tool name collision: '" + previous + "' and '" + id
                                + "' both encode to '" + wire + "'");
            }
        }
        return Map.copyOf(byWireName);
    }

    /**
     * Decodes a name the model called back to its capability id.
     *
     * <p>Falls back to the wire name itself when the lookup misses — a model can hallucinate a tool
     * name, and that must reach the kernel as an ordinary "unknown capability" denial rather than
     * being silently rewritten into something that exists.
     */
    public static String fromWire(String wireName, Map<String, String> mapping) {
        Objects.requireNonNull(wireName, "wireName");
        Objects.requireNonNull(mapping, "mapping");
        return mapping.getOrDefault(wireName, wireName);
    }

    /** Whether a name is already acceptable to a model API. */
    public static boolean isWireSafe(String name) {
        return name != null && WIRE_SAFE.matcher(name).matches();
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, HASH_LENGTH / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
