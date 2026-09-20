// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.capability;

import io.jclaw.ports.Result;
import io.jclaw.ports.capability.CapabilityDescriptor;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityInvocation;
import io.jclaw.ports.capability.EffectClass;
import io.jclaw.ports.capability.HandlerError;
import io.jclaw.ports.skill.SkillCatalog;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Skill capabilities: the disclosure half of progressive disclosure.
 *
 * <p>The system prompt advertises each skill's summary; these let the model pull the full body
 * when it decides one applies. Both are {@link EffectClass#PURE} — reading an installed
 * instruction file changes nothing — so they run under every policy including the strictest.
 *
 * <p>Worth being explicit about the trust position: skill text is author-controlled content that
 * lands in the transcript and steers subsequent behaviour. It is a prompt-injection surface by
 * design, which is the point of a skill, so installing one is exactly as consequential as editing
 * the system prompt and should be treated that way.
 */
public final class SkillTools {

    private SkillTools() {
    }

    public static List<CapabilityHandler> all(SkillCatalog catalog) {
        return List.of(new ListSkills(catalog), new ReadSkill(catalog));
    }

    /** Lists installed skills. Redundant with the system prompt, but useful mid-conversation. */
    public static final class ListSkills implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "skill_list",
                "List the installed skills and when each applies.",
                EffectClass.PURE,
                Schemas.noArguments());

        private final SkillCatalog catalog;

        public ListSkills(SkillCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            List<SkillCatalog.Skill> skills = catalog.list();
            if (skills.isEmpty()) {
                return Result.ok("(no skills installed)");
            }
            return Result.ok(skills.stream()
                    .map(skill -> "- " + skill.summary())
                    .collect(Collectors.joining("\n")));
        }
    }

    /** Loads a skill's full instructions. */
    public static final class ReadSkill implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "skill_read",
                "Load a skill's full instructions by id. Do this before following a skill.",
                EffectClass.PURE,
                Schemas.object(
                        Schemas.properties("id", Schemas.string("Skill id, as listed in the system prompt.")),
                        List.of("id")));

        private final SkillCatalog catalog;

        public ReadSkill(SkillCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String id = invocation.stringArg("id", "");
            if (id.isBlank()) {
                return Result.err(HandlerError.failed("id_required"));
            }
            return catalog.find(id)
                    .map(skill -> Result.<String, HandlerError>ok(
                            "# " + skill.name() + "\n\n" + skill.instructions()))
                    .orElseGet(() -> Result.err(HandlerError.failed("skill_not_found")));
        }
    }
}
