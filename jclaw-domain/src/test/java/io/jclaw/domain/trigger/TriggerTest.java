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
        assertTrue(Trigger.parse("on model.called").errorAsOptional().orElseThrow().startsWith("invalid_trigger"),
                "only the three safe event types may drive a trigger; one a routine's own run "
                        + "emits off a routine thread would let a chain form");
        assertEquals("event_filter_needs_key=value", Trigger.parse("on run.finished failed").errorAsOptional().orElseThrow());
        assertTrue(Trigger.parse("61 9 * * *").errorAsOptional().orElseThrow().startsWith("invalid_trigger"));
    }

    @Test
    @DisplayName("a watch parses, round-trips, and stays inside the workspace")
    void watches() {
        Trigger.Watch watch = (Trigger.Watch) Trigger.parse("watch src/**/*.java").orElseThrow();
        assertEquals("src/**/*.java", watch.glob());
        assertEquals(watch, Trigger.parse(watch.expression()).orElseThrow());
        assertFalse(watch.timeDriven(), "a file change decides when this fires, not the clock");

        assertEquals("watch_needs_a_glob", Trigger.parse("watch").errorAsOptional().orElseThrow());
        assertTrue(Trigger.parse("watch /etc/passwd").errorAsOptional().orElseThrow()
                .startsWith("invalid_trigger"), "a watch is relative to the workspace");
        assertTrue(Trigger.parse("watch ../../**").errorAsOptional().orElseThrow()
                .startsWith("invalid_trigger"), "and cannot climb out of it");
    }

    @Test
    @DisplayName("a webhook topic is optional, round-trips, and only matches another of the same")
    void webhookTopics() {
        String hash = Trigger.hashSecret("s3cret");
        Trigger.Webhook plain = (Trigger.Webhook) Trigger.parse("webhook sha256:" + hash).orElseThrow();
        assertEquals("", plain.topic());

        Trigger.Webhook topical =
                (Trigger.Webhook) Trigger.parse("webhook sha256:" + hash + " topic=deploys").orElseThrow();
        assertEquals("deploys", topical.topic());
        assertEquals(topical, Trigger.parse(topical.expression()).orElseThrow());

        assertTrue(topical.sharesTopicWith(new Trigger.Webhook(Trigger.hashSecret("other"), "deploys")),
                "a different secret, the same topic: that is the subscription");
        assertFalse(topical.sharesTopicWith(new Trigger.Webhook(hash, "releases")));
        assertFalse(plain.sharesTopicWith(new Trigger.Webhook(hash, "")),
                "a blank topic subscribes to nothing, so two of them are not a fan-out group");

        assertEquals("webhook_takes_only_topic=name",
                Trigger.parse("webhook sha256:" + hash + " something=else").errorAsOptional().orElseThrow());
        assertTrue(Trigger.parse("webhook sha256:" + hash + " topic=UPPERCASE")
                .errorAsOptional().orElseThrow().startsWith("invalid_trigger"),
                "a topic is a name, and names here are lower-case");
        assertEquals("webhook_takes_only_topic=name",
                Trigger.parse("webhook sha256:" + hash + " topic=has space").errorAsOptional().orElseThrow(),
                "a topic with a space is two words, and the second is not topic=");
    }

    @Test
    @DisplayName("turn.submitted is a trigger type, so an inbound message can fire a routine")
    void inboundMessageTrigger() {
        Trigger.OnEvent on = (Trigger.OnEvent) Trigger.parse("on turn.submitted thread=ops").orElseThrow();
        assertTrue(on.matches("turn.submitted", java.util.Map.of("thread", "ops")));
        assertFalse(on.matches("turn.submitted", java.util.Map.of("thread", "other")));
        assertFalse(on.matches("run.finished", java.util.Map.of("thread", "ops")));
    }
}
