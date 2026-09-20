// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.capability;

import io.jclaw.ports.Result;
import io.jclaw.ports.capability.CapabilityDescriptor;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityInvocation;
import io.jclaw.ports.capability.EffectClass;
import io.jclaw.ports.capability.HandlerError;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Effect-free capabilities.
 *
 * <p>Both are {@link EffectClass#PURE}, so they run unattended under every policy including the
 * strictest. {@code echo} exists because a harness needs one call that provably works when
 * diagnosing whether the loop, the provider, or the tool layer is at fault.
 */
public final class CoreTools {

    private CoreTools() {
    }

    public static List<CapabilityHandler> all(Clock clock) {
        return List.of(new Echo(), new Time(clock));
    }

    /** Returns its input unchanged. The harness's canary. */
    public static final class Echo implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "echo",
                "Return the given text unchanged. Useful for testing tool dispatch.",
                EffectClass.PURE,
                Schemas.object(
                        Schemas.properties("text", Schemas.string("Text to echo back.")),
                        List.of("text")));

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            return Result.ok(invocation.stringArg("text", ""));
        }
    }

    /**
     * Reports the current time.
     *
     * <p>Takes a {@link Clock} rather than calling {@code Instant.now()} so tests can pin it — the
     * same discipline the domain layer follows, applied here because a tool that reads a hidden
     * global clock is a tool whose output cannot be reproduced.
     */
    public static final class Time implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "time",
                "Get the current date and time, optionally in a named IANA time zone.",
                EffectClass.PURE,
                Schemas.object(
                        Schemas.properties("zone", Schemas.string(
                                "IANA time zone, e.g. 'Europe/Rome'. Defaults to UTC.")),
                        List.of()));

        private final Clock clock;

        public Time(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String zoneName = invocation.stringArg("zone", "UTC");
            ZoneId zone;
            try {
                zone = ZoneId.of(zoneName);
            } catch (RuntimeException e) {
                return Result.err(HandlerError.failed("unknown_time_zone"));
            }
            ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
            return Result.ok(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " (" + zone + ")");
        }
    }
}
