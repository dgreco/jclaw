package io.jclaw.app.cli;

import io.jclaw.contracts.skill.SkillCatalog;
import io.jclaw.storage.skill.FilesystemSkillCatalog;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Inspects installed skills.
 *
 * <p>Installation is deliberately not a command: a skill is a directory containing
 * {@code SKILL.md}, so installing one is {@code cp -r}, and removing one is {@code rm -r}. Wrapping
 * that in a subcommand would add surface without adding capability, and it would obscure that a
 * skill is just a file the operator owns.
 */
@Component
@Command(
        name = "skills",
        description = "List and inspect installed skills.",
        mixinStandardHelpOptions = true,
        subcommands = {SkillsCommand.ListSkills.class, SkillsCommand.Show.class})
public class SkillsCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Component
    @Command(name = "list", description = "List installed skills.", mixinStandardHelpOptions = true)
    public static class ListSkills implements Callable<Integer> {

        private final FilesystemSkillCatalog catalog;

        public ListSkills(FilesystemSkillCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public Integer call() {
            List<SkillCatalog.Skill> skills = catalog.list();
            if (skills.isEmpty()) {
                System.out.println("(no skills installed)");
                System.out.println();
                System.out.println("Add one by creating " + catalog.root().resolve("<id>/SKILL.md"));
                return 0;
            }
            for (SkillCatalog.Skill skill : skills) {
                System.out.printf("%-24s %s%n", skill.id(), skill.description());
                if (!skill.whenToUse().isBlank()) {
                    System.out.println("    use when: " + skill.whenToUse());
                }
            }
            System.out.println();
            System.out.println(skills.size() + " installed in " + catalog.root());
            return 0;
        }
    }

    @Component
    @Command(name = "show", description = "Print a skill's full instructions.",
            mixinStandardHelpOptions = true)
    public static class Show implements Callable<Integer> {

        private final FilesystemSkillCatalog catalog;

        @Parameters(index = "0", description = "Skill id.")
        private String id;

        public Show(FilesystemSkillCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public Integer call() {
            return catalog.find(id)
                    .map(skill -> {
                        System.out.println("# " + skill.name());
                        System.out.println();
                        System.out.println(skill.instructions());
                        return 0;
                    })
                    .orElseGet(() -> {
                        System.err.println("jclaw: no such skill: " + id);
                        return 1;
                    });
        }
    }
}
