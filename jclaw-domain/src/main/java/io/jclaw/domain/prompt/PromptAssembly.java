package io.jclaw.domain.prompt;

import io.jclaw.contracts.skill.SkillCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Pure assembly of the system prompt.
 *
 * <p>A function from context to a string: no I/O, no clock, no ports. Two consequences. The prompt
 * for any given configuration is reproducible and diffable, which matters because a prompt change
 * alters agent behaviour as surely as a code change. And it is testable by assertion rather than
 * by running an agent and reading the tea leaves.
 *
 * <p>Section order is deliberate and stable. Prompt caching matches on prefixes, so the parts that
 * rarely change (identity, workspace) come first and the parts that change more often (skills)
 * come last. Reordering these on a whim silently destroys cache hit rates.
 */
public final class PromptAssembly {

    private PromptAssembly() {
    }

    /**
     * Builds the system prompt.
     *
     * @param base          the operator-configured instructions
     * @param workspaceName display name of the workspace root; never an absolute host path, which
     *                      would leak the user's directory layout into every request
     * @param skills        installed skills, summarized rather than inlined
     */
    public static String systemPrompt(
            String base, String workspaceName, List<SkillCatalog.Skill> skills) {

        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(workspaceName, "workspaceName");
        Objects.requireNonNull(skills, "skills");

        StringBuilder prompt = new StringBuilder(base.strip());

        prompt.append("\n\n## Workspace\n")
                .append("You are operating in the workspace '")
                .append(workspaceName)
                .append("'. All file paths are relative to it, and access outside it is denied.");

        if (!skills.isEmpty()) {
            prompt.append("\n\n## Available skills\n")
                    .append("These are instruction sets you can load when a task calls for one. ")
                    .append("Only their summaries are shown; call `builtin.skill_read` with a skill ")
                    .append("id to load its full instructions before following it.\n");
            skills.stream()
                    .sorted(java.util.Comparator.comparing(SkillCatalog.Skill::id))
                    .forEach(skill -> prompt.append("\n- ").append(skill.summary()));
        }

        return prompt.toString();
    }

    /**
     * Workspace display name from a root path.
     *
     * <p>The directory name only. An absolute path in the system prompt would put the operator's
     * home directory and username into every outbound request, for no benefit to the model.
     */
    public static String workspaceName(Path root) {
        Objects.requireNonNull(root, "root");
        Path name = root.toAbsolutePath().normalize().getFileName();
        return name == null ? "workspace" : name.toString();
    }
}
