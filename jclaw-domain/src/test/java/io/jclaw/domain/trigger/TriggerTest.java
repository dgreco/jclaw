package io.jclaw.domain.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerTest {

    @Test
    @DisplayName("each form parses, round-trips through its expression, and knows whether the clock drives it")
    void parsesForms() {
        Trigger cron = Trigger.parse("0 9 * * MON-FRI").orElseThrow();
        assertEquals(new Trigger.Cron("0 9 * * MON-FRI"), cron);
        assertTrue(cron.timeDriven());

        Trigger heartbeat = Trigger.parse("every 30m").orElseThrow();
        assertEquals(new Trigger.Heartbeat(Duration.ofMinutes(30)), heartbeat);
        assertEquals(new Trigger.Heartbeat(Duration.ofHours(2)), Trigger.parse("every PT2H").orElseThrow());
        assertEquals("every PT30M", heartbeat.expression());
        assertEquals(heartbeat, Trigger.parse(heartbeat.expression()).orElseThrow());

        String hash = Trigger.hashSecret("s3cret");
        Trigger webhook = Trigger.parse("webhook sha256:" + hash).orElseThrow();
        assertFalse(webhook.timeDriven());
        assertTrue(((Trigger.Webhook) webhook).accepts("s3cret"));
        assertFalse(((Trigger.Webhook) webhook).accepts("other"));
        assertFalse(((Trigger.Webhook) webhook).accepts(null));
        assertEquals(webhook, Trigger.parse(webhook.expression()).orElseThrow());

        Trigger.OnEvent event = (Trigger.OnEvent) Trigger.parse("on run.finished status=FAILED").orElseThrow();
        assertEquals("run.finished", event.type());
        assertEquals(Map.of("status", "FAILED"), event.filters());
        assertTrue(event.matches("run.finished", Map.of("status", "failed")));
        assertFalse(event.matches("run.finished", Map.of("status", "COMPLETED")));
        assertFalse(event.matches("gate.raised", Map.of("status", "FAILED")));
        assertEquals(event, Trigger.parse(event.expression()).orElseThrow());
    }

    @Test
    @DisplayName("nonsense is refused with a reason")
    void refuses() {
        assertEquals("trigger_required", Trigger.parse("  ").errorAsOptional().orElseThrow());
        assertEquals("heartbeat_needs_one_interval", Trigger.parse("every").errorAsOptional().orElseThrow());
        assertTrue(Trigger.parse("every fortnight").errorAsOptional().orElseThrow().startsWith("invalid_trigger"));
        assertEquals("webhook_needs_sha256_hash", Trigger.parse("webhook plain").errorAsOptional().orElseThrow());
        assertTrue(Trigger.parse("on turn.submitted").errorAsOptional().orElseThrow().startsWith("invalid_trigger"),
                "only the two safe event types may drive a trigger");
        assertEquals("event_filter_needs_key=value", Trigger.parse("on run.finished failed").errorAsOptional().orElseThrow());
        assertTrue(Trigger.parse("61 9 * * *").errorAsOptional().orElseThrow().startsWith("invalid_trigger"));
    }
}
