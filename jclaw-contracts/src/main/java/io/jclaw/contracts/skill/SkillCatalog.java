package io.jclaw.contracts.skill;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The skills available to an agent.
 *
 * <p>A skill is packaged instructions — a checklist, a house style, a procedure — that the agent
 * loads when a task calls for it.
 *
 * <p>Deliberately built around <b>progressive disclosure</b>: only each skill's name, description,
 * and {@code whenToUse} go into the system prompt, and the full instructions are fetched through a
 * capability if the model decides the skill applies. Two reasons, and the second is the one that
 * shaped the design:
 *
 * <ul>
 *   <li>Inlining every skill's body would consume the context window with instructions that are
 *       almost always irrelevant to the current turn.</li>
 *   <li>A skill activated mid-run would otherwise have to mutate the system prompt, and this
 *       harness fixes the run profile at admission precisely so a resumed run cannot acquire
 *       instructions it did not start with. Fetching a skill as a tool result keeps it inside the
 *       transcript, where it is visible, auditable, and replayed identically.</li>
 * </ul>
 */
public interface SkillCatalog {

    /**
     * One skill.
     *
     * @param whenToUse    one line telling the model when this applies; the only selection signal
     *                     it gets before loading the body
     * @param instructions the full body, disclosed only on request
     */
    record Skill(String id, String name, String description, String whenToUse, String instructions) {

        public Skill {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(whenToUse, "whenToUse");
            Objects.requireNonNull(instructions, "instructions");
            if (id.isBlank()) {
                throw new IllegalArgumentException("skill id must not be blank");
            }
        }

        /** The one-line form that goes into the system prompt. */
        public String summary() {
            return whenToUse.isBlank()
                    ? id + " - " + description
                    : id + " - " + description + " (use when: " + whenToUse + ")";
        }
    }

    /** Every installed skill, ordered by id. */
    List<Skill> list();

    /** Looks up a skill by id. */
    Optional<Skill> find(String id);
}
