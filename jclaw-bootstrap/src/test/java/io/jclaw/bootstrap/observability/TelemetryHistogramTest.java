// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exposition has to be one a scraper will actually accept: cumulative buckets, a mandatory
 * {@code +Inf} equal to the count, and exactly one {@code # TYPE} line per metric name.
 */
class TelemetryHistogramTest {

    private static String lineFor(String text, String prefix) {
        return Arrays.stream(text.split("\n"))
                .filter(line -> line.startsWith(prefix))
                .findFirst().orElseThrow(() -> new AssertionError("no line starting '" + prefix + "' in:\n" + text));
    }

    private static long valueOf(String line) {
        return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
    }

    @Test
    @DisplayName("buckets are cumulative and +Inf equals the count")
    void bucketsAreCumulative() {
        Telemetry telemetry = new Telemetry();
        telemetry.time("jclaw_test_millis", 3);
        telemetry.time("jclaw_test_millis", 30);
        telemetry.time("jclaw_test_millis", 3_000);
        String text = telemetry.prometheus();

        assertEquals(0, valueOf(lineFor(text, "jclaw_test_millis_bucket{le=\"2\"}")));
        assertEquals(1, valueOf(lineFor(text, "jclaw_test_millis_bucket{le=\"5\"}")));
        assertEquals(1, valueOf(lineFor(text, "jclaw_test_millis_bucket{le=\"25\"}")));
        assertEquals(2, valueOf(lineFor(text, "jclaw_test_millis_bucket{le=\"50\"}")),
                "a value counts in its own bucket and every wider one");
        assertEquals(3, valueOf(lineFor(text, "jclaw_test_millis_bucket{le=\"5000\"}")));
        assertEquals(3, valueOf(lineFor(text, "jclaw_test_millis_bucket{le=\"+Inf\"}")));
        assertEquals(3, valueOf(lineFor(text, "jclaw_test_millis_count")));
        assertEquals(3_033, valueOf(lineFor(text, "jclaw_test_millis_sum")));
        assertEquals(3_000, valueOf(lineFor(text, "jclaw_test_millis_max")));
    }

    @Test
    @DisplayName("a value beyond the last finite bucket still lands in +Inf")
    void overflowLandsInInf() {
        Telemetry telemetry = new Telemetry();
        telemetry.time("jclaw_slow_millis", 120_000);
        String text = telemetry.prometheus();
        assertEquals(0, valueOf(lineFor(text, "jclaw_slow_millis_bucket{le=\"60000\"}")));
        assertEquals(1, valueOf(lineFor(text, "jclaw_slow_millis_bucket{le=\"+Inf\"}")));
        assertEquals(1, valueOf(lineFor(text, "jclaw_slow_millis_count")));
    }

    @Test
    @DisplayName("labels are kept and le is appended to them, not replacing them")
    void labelsSurvive() {
        Telemetry telemetry = new Telemetry();
        telemetry.time("jclaw_capability_latency_millis", 7, "capability", "builtin.shell");
        String text = telemetry.prometheus();
        assertTrue(text.contains("jclaw_capability_latency_millis_bucket{capability=\"builtin.shell\",le=\"10\"} 1"),
                text);
        assertTrue(text.contains("jclaw_capability_latency_millis_count{capability=\"builtin.shell\"} 1"), text);
    }

    @Test
    @DisplayName("one TYPE line per metric name, however many label sets it has")
    void oneTypeLinePerName() {
        Telemetry telemetry = new Telemetry();
        telemetry.time("jclaw_model_latency_millis", 10, "provider", "anthropic");
        telemetry.time("jclaw_model_latency_millis", 20, "provider", "openai");
        telemetry.time("jclaw_capability_latency_millis", 1);

        List<String> types = Arrays.stream(telemetry.prometheus().split("\n"))
                .filter(line -> line.startsWith("# TYPE")).toList();
        assertEquals(List.of(
                        "# TYPE jclaw_capability_latency_millis histogram",
                        "# TYPE jclaw_model_latency_millis histogram"),
                types,
                "a second TYPE line for one name makes a scraper reject the whole scrape");
    }

    @Test
    @DisplayName("counters are untouched by the histogram change")
    void countersStillRender() {
        Telemetry telemetry = new Telemetry();
        telemetry.count("jclaw_runs_finished_total", "status", "COMPLETED");
        telemetry.add("jclaw_model_tokens_total", 42, "kind", "input");
        String text = telemetry.prometheus();
        assertTrue(text.contains("jclaw_runs_finished_total{status=\"completed\"} 1"), text);
        assertTrue(text.contains("jclaw_model_tokens_total{kind=\"input\"} 42"), text);
    }
}
