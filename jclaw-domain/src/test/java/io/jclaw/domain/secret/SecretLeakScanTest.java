// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.secret;

import io.jclaw.contracts.secret.SecretVault.SecretName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretLeakScanTest {

    private static final SecretName GH = new SecretName("gh");
    private static final SecretName SHORT = new SecretName("pin");

    private static Map<SecretName, String> vault() {
        Map<SecretName, String> values = new LinkedHashMap<>();
        values.put(GH, "ghp_0123456789abcdef");
        values.put(SHORT, "1234");
        return values;
    }

    @Test
    @DisplayName("a value that a tool echoed back is found wherever it sits in the text")
    void findsEchoedValues() {
        assertEquals(Set.of(GH), SecretLeakScan.leaks(
                "the server replied: Authorization: Bearer ghp_0123456789abcdef", vault()));
        assertEquals(Set.of(), SecretLeakScan.leaks("nothing to see here", vault()));
        assertEquals(Set.of(), SecretLeakScan.leaks("", vault()));
    }

    @Test
    @DisplayName("values below the threshold are not scanned, and that is deliberate")
    void shortValuesAreABlindSpot() {
        assertTrue(SecretLeakScan.leaks("the answer is 1234", vault()).isEmpty(),
                "a four-character secret matches prose by coincidence; firing on it would be noise");
        assertEquals("the answer is 1234", SecretLeakScan.redact("the answer is 1234", vault()));
        assertEquals(8, SecretLeakScan.MINIMUM_LENGTH);
    }

    @Test
    @DisplayName("redaction leaves the reference form, which is both true and useful")
    void redactsToReference() {
        assertEquals("token {{secret:gh}} was echoed",
                SecretLeakScan.redact("token ghp_0123456789abcdef was echoed", vault()));
        assertEquals("{{secret:gh}} and {{secret:gh}}",
                SecretLeakScan.redact("ghp_0123456789abcdef and ghp_0123456789abcdef", vault()),
                "every occurrence, not just the first");
        assertFalse(SecretLeakScan.redact("ghp_0123456789abcdef", vault()).contains("ghp_"));
    }

    @Test
    @DisplayName("a null value in the map is skipped rather than thrown on")
    void tolerantOfMissingValues() {
        Map<SecretName, String> withNull = new LinkedHashMap<>();
        withNull.put(GH, null);
        assertTrue(SecretLeakScan.leaks("anything at all", withNull).isEmpty());
        assertEquals("anything at all", SecretLeakScan.redact("anything at all", withNull));
    }
}
