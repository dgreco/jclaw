package io.jclaw.domain.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchStateTest {

    private static Map<String, Long> tree(Object... pairs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return out;
    }

    @Test
    @DisplayName("the same tree fingerprints the same, whatever order the walk produced")
    void orderIndependent() {
        assertEquals(WatchState.fingerprint(tree("a.java", 1, "b.java", 2)),
                WatchState.fingerprint(tree("b.java", 2, "a.java", 1)),
                "a directory walk does not promise an order, and a fingerprint that depended on "
                        + "one would fire on every tick");
        assertEquals(WatchState.fingerprint(Map.of()), WatchState.fingerprint(Map.of()));
    }

    @Test
    @DisplayName("a changed time, a new file, a deleted file, and a rename all change it")
    void everyChangeShows() {
        String base = WatchState.fingerprint(tree("a.java", 1, "b.java", 2));
        assertNotEquals(base, WatchState.fingerprint(tree("a.java", 9, "b.java", 2)), "edited");
        assertNotEquals(base, WatchState.fingerprint(tree("a.java", 1, "b.java", 2, "c.java", 3)), "added");
        assertNotEquals(base, WatchState.fingerprint(tree("a.java", 1)), "deleted");
        assertNotEquals(base, WatchState.fingerprint(tree("a.java", 1, "renamed.java", 2)), "renamed");
    }

    @Test
    @DisplayName("the first look never fires")
    void firstLookIsABaseline() {
        String now = WatchState.fingerprint(tree("a.java", 1));
        assertFalse(WatchState.changed(Optional.empty(), now),
                "a watch over an existing tree must not fire on nothing having happened");
        assertFalse(WatchState.changed(Optional.of(now), now));
        assertTrue(WatchState.changed(Optional.of(now), WatchState.fingerprint(tree("a.java", 2))));
    }
}
