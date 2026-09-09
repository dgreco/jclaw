package io.jclaw.domain.trigger;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deciding whether a watched tree has changed since the last look, purely.
 *
 * <p>The state is one number per watch: a fingerprint of the matching files and their
 * modification times. Comparing fingerprints answers "has anything changed" without keeping the
 * file list, which matters because a watch over a source tree can match thousands of paths and
 * the worker holds this between ticks.
 *
 * <p>Deletions count. A fingerprint over paths and times, not just times, changes when a file
 * disappears — which is what an operator means by "the tree changed" and what a naive
 * newest-mtime check gets wrong.
 *
 * <p>The first look never fires. A watch created against an existing tree would otherwise fire
 * immediately on nothing having happened, and an operator would learn to ignore it.
 */
public final class WatchState {

    private WatchState() {
    }

    /**
     * A fingerprint of what a watch currently matches.
     *
     * @param modifiedTimes epoch milliseconds by path, relative and {@code /}-separated
     */
    public static String fingerprint(Map<String, Long> modifiedTimes) {
        Objects.requireNonNull(modifiedTimes, "modifiedTimes");
        // Order-independent: a directory walk does not promise one, and a fingerprint that
        // depended on it would fire on every tick.
        long hash = 1469598103934665603L;
        for (Map.Entry<String, Long> entry : new TreeMap<>(modifiedTimes).entrySet()) {
            hash = mix(hash, entry.getKey());
            hash = mix(hash, String.valueOf(entry.getValue()));
        }
        return Long.toHexString(hash) + ":" + modifiedTimes.size();
    }

    /**
     * Whether a watch should fire, given what it saw last time.
     *
     * @param previous the fingerprint from the last tick, or empty on the first look
     * @param current  the fingerprint now
     */
    public static boolean changed(Optional<String> previous, String current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        return previous.isPresent() && !previous.get().equals(current);
    }

    private static long mix(long hash, String text) {
        long out = hash;
        for (int i = 0; i < text.length(); i++) {
            out ^= text.charAt(i);
            out *= 1099511628211L;
        }
        return out ^ 0x2f;
    }
}
