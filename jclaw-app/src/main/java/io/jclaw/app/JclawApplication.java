// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;

import java.util.List;
import picocli.CommandLine.IFactory;

/**
 * Process entry point and composition root.
 *
 * <p>Spring owns the object graph; picocli owns argv. The bridge is
 * {@link IFactory}, supplied by {@code picocli-spring-boot-starter}, which lets picocli resolve
 * {@code @Command} classes as Spring beans so subcommands get constructor injection like anything
 * else.
 *
 * <p>Configured as a non-web application with the banner suppressed: this is a CLI, and a startup
 * banner on stdout would corrupt output that callers may pipe.
 */
@SpringBootApplication
public class JclawApplication implements CommandLineRunner, ExitCodeGenerator {

    private final IFactory factory;
    private final JclawCommand command;
    private int exitCode;

    public JclawApplication(IFactory factory, JclawCommand command) {
        this.factory = factory;
        this.command = command;
    }

    public static void main(String[] args) {
        // Set when the JVM is already exiting (Ctrl-C on a long-running command). A second
        // System.exit from inside a shutdown is at best a hang and at worst a stack trace after
        // "stopped."; returning lets the shutdown that is already under way finish.
        java.util.concurrent.atomic.AtomicBoolean shuttingDown = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shuttingDown.set(true), "jclaw-shutdown-flag"));
        try {
            org.springframework.context.ConfigurableApplicationContext context =
                    new SpringApplicationBuilder(JclawApplication.class)
                            .web(WebApplicationType.NONE)
                            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                            .logStartupInfo(false)
                            .run(expandVerbosityFlags(args));
            if (shuttingDown.get()) {
                return;
            }
            System.exit(SpringApplication.exit(context));
        } catch (SpringApplication.AbandonedRunException e) {
            // Not a failure. Spring throws this to abort the run during AOT processing, once the
            // context has been built for native-image generation. Swallowing it as a config error
            // makes `mvn -Pnative` fail with a misleading message.
            throw e;
        } catch (RuntimeException e) {
            if (shuttingDown.get()) {
                return; // the interrupt already ended the process; nothing to report
            }
            // Startup failures are almost always misconfiguration — a bad approval mode, an
            // unreadable workspace. A CLI should say which, in one line. A 40-frame Spring trace
            // buries the one sentence that matters and can print host paths along the way.
            System.err.println("jclaw: " + rootMessage(e));
            System.exit(CommandLine.ExitCode.USAGE);
        }
    }

    /** The innermost message, which is the one describing what the user actually got wrong. */
    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    @Override
    public void run(String... args) {
        exitCode = new CommandLine(command, factory)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((exception, commandLine, parseResult) -> {
                    // A stack trace on a CLI is noise, and it can carry paths. Report the message.
                    commandLine.getErr().println("jclaw: " + exception.getMessage());
                    return CommandLine.ExitCode.SOFTWARE;
                })
                .execute(withoutSpringProperties(args));
    }

    /**
     * Rewrites {@code --debug} / {@code --trace} into the {@code --logging.*} properties they
     * abbreviate, before Spring binds the environment.
     *
     * <p>{@code --debug} narrates the pipeline at DEBUG — every state-machine step, decision,
     * dispatch, and timing. {@code --trace} adds TRACE: payloads too — prompts, messages, tool
     * arguments, and results (redacted and bounded before logging, like everything else that
     * leaves the host). Both switch the console pattern to a timestamped one, because bare
     * {@code %msg} lines are unreadable once dozens of them describe one turn.
     *
     * <p>Expanding into {@code --logging.*} arguments rather than setting system properties keeps
     * one configuration path: the expanded form is exactly what a user could have typed, and
     * {@link #withoutSpringProperties} already hides that namespace from picocli. Replacing the
     * literal {@code --debug} also keeps it away from Spring, whose own {@code --debug} would
     * print a condition-evaluation report no CLI user asked for. {@code --verbose} is not claimed
     * here because {@code jclaw tools --verbose} already owns that name.
     */
    static String[] expandVerbosityFlags(String... args) {
        return java.util.Arrays.stream(args)
                .flatMap(arg -> switch (arg) {
                    case "--trace" -> java.util.stream.Stream.of(
                            "--logging.level.io.jclaw=TRACE", VERBOSE_CONSOLE_PATTERN);
                    case "--debug" -> java.util.stream.Stream.of(
                            "--logging.level.io.jclaw=DEBUG", VERBOSE_CONSOLE_PATTERN);
                    default -> java.util.stream.Stream.of(arg);
                })
                .toArray(String[]::new);
    }

    /** Timestamp, level, and class for verbose runs; plain {@code %msg} stays the default. */
    private static final String VERBOSE_CONSOLE_PATTERN =
            "--logging.pattern.console=%d{HH:mm:ss.SSS} %-5level %logger{0} : %msg%n";

    /**
     * Spring config namespaces whose {@code --a.b=c} arguments Spring binds from the environment.
     * They reach {@code CommandLineRunner} as well, where picocli would reject them as unknown.
     */
    private static final List<String> SPRING_PREFIXES =
            List.of("--jclaw.", "--spring.", "--logging.", "--management.", "--server.");

    /**
     * Strips arguments Spring has already consumed.
     *
     * <p>Both frameworks are handed the same argv: Spring binds {@code --jclaw.workspace=...} into
     * the environment, and picocli owns the actual CLI flags. Without this filter every Spring
     * property override would fail the command with "Unknown options", which makes
     * {@code --jclaw.provider=anthropic} — the documented way to switch providers — unusable.
     */
    static String[] withoutSpringProperties(String... args) {
        return java.util.Arrays.stream(args)
                .filter(arg -> SPRING_PREFIXES.stream().noneMatch(arg::startsWith))
                .toArray(String[]::new);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
