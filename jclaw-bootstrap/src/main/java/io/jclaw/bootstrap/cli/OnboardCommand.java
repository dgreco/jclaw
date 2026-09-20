// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.config.JclawProperties;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Guided first-run setup.
 *
 * <p>Writes {@code ~/.jclaw/jclaw.yaml}, which {@code application.yaml} imports optionally. That
 * location is fixed rather than derived from {@code jclaw.state-dir}, because a config file has to
 * be found <em>before</em> configuration is read — deriving its path from a property it defines
 * would be circular.
 *
 * <p>Reads from stdin rather than a terminal library so it works when piped, which is also how it
 * gets tested. Never asks for an API key: keys belong in the environment or a secret manager, and
 * a config file that contains one is a config file that ends up in a git repository.
 */
@Component
@Command(
        name = "onboard",
        description = "Interactive first-run setup. Writes ~/.jclaw/jclaw.yaml.",
        mixinStandardHelpOptions = true)
public class OnboardCommand implements Callable<Integer> {

    private static final List<String> PROVIDERS =
            List.of("mock", "anthropic", "openai", "openrouter", "ollama", "local", "failover");
    private static final List<String> MODES = List.of("read-only", "interactive", "trusted");

    private final JclawProperties properties;

    @Option(names = "--force", description = "Overwrite an existing config file.")
    private boolean force;

    @Option(names = "--print", description = "Show the config that would be written, without writing it.")
    private boolean dryRun;

    public OnboardCommand(JclawProperties properties) {
        this.properties = properties;
    }

    @Override
    public Integer call() throws IOException {
        Path target = Path.of(System.getProperty("user.home"), ".jclaw", "jclaw.yaml");

        if (Files.exists(target) && !force && !dryRun) {
            System.err.println("jclaw: " + target + " already exists. Re-run with --force to replace it.");
            return 1;
        }

        System.out.println("jclaw onboarding");
        System.out.println();

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String provider = choose(in, "Model provider", PROVIDERS, properties.provider());
            String model = ask(in, "Model id", defaultModelFor(provider));
            String mode = choose(in, "Approval mode", MODES, properties.approvalMode());
            // The local provider is unusable without its base URL, so onboarding must collect it
            // now — writing a config that fails on the very next command is not onboarding.
            String localBaseUrl = "local".equals(provider)
                    ? ask(in, "Server base URL", defaultLocalBaseUrl())
                    : "";

            String config = render(provider, model, mode, localBaseUrl);

            System.out.println();
            System.out.println("--- " + target + " ---");
            System.out.print(config);
            System.out.println("---");

            if (dryRun) {
                System.out.println();
                System.out.println("Not written (--print).");
                return 0;
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, config, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("Wrote " + target);
            printCredentialHint(provider);
            return 0;
        }
    }

    /** The config file itself, with the credential deliberately absent. */
    private static String render(String provider, String model, String mode, String localBaseUrl) {
        String config = """
                # Written by 'jclaw onboard'. Imported automatically by jclaw.
                # Credentials are intentionally not stored here - set them in the environment.
                jclaw:
                  provider: %s
                  model: %s
                  approval-mode: %s
                """.formatted(provider, model, mode);
        // A URL is topology, not a credential, so it belongs in the file.
        return localBaseUrl.isBlank()
                ? config
                : config + "  local-base-url: " + localBaseUrl + "\n";
    }

    /** Suggested base URL for the local provider: the configured one, else LM Studio's default. */
    private String defaultLocalBaseUrl() {
        return properties.localBaseUrl().isBlank()
                ? "http://localhost:1234/v1"
                : properties.localBaseUrl();
    }

    private static void printCredentialHint(String provider) {
        switch (provider) {
            case "anthropic", "failover" -> System.out.println(
                    "Next: export ANTHROPIC_API_KEY=... (or run 'ant auth login').");
            case "openai" -> System.out.println("Next: export OPENAI_API_KEY=...");
            case "openrouter" -> {
                System.out.println("Next: export OPENROUTER_API_KEY=...");
                System.out.println("Model ids are namespaced, e.g. anthropic/claude-sonnet-4.6.");
            }
            case "ollama" -> System.out.println("Next: make sure Ollama is running locally.");
            case "local" -> {
                System.out.println("Next: make sure the server is running and serving the model id above.");
                System.out.println("If it checks a bearer token (e.g. vLLM --api-key), export LOCAL_API_KEY=...");
            }
            default -> System.out.println("The mock provider needs no credentials. Try: jclaw run \"hello\"");
        }
        System.out.println("Verify with: jclaw models --probe");
    }

    private static String defaultModelFor(String provider) {
        return switch (provider) {
            case "openai" -> "gpt-4o";
            // OpenRouter requires the org/model form; suggesting a bare id would 404.
            case "openrouter" -> "anthropic/claude-sonnet-4.6";
            case "ollama" -> "llama3.2";
            // Local servers name models freely; suggest the id LM Studio shows for a loaded model.
            case "local" -> "qwen2.5-coder-7b-instruct";
            default -> "claude-opus-5";
        };
    }

    /** Prompts until the answer is one of {@code options}, defaulting on empty input. */
    private static String choose(BufferedReader in, String label, List<String> options, String fallback)
            throws IOException {
        while (true) {
            System.out.printf("%s %s [%s]: ", label, options, fallback);
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                // EOF (piped input exhausted): take the default rather than looping forever.
                System.out.println(fallback);
                return fallback;
            }
            String answer = line.trim();
            if (answer.isEmpty()) {
                return fallback;
            }
            if (options.contains(answer)) {
                return answer;
            }
            System.out.println("  not one of " + options);
        }
    }

    private static String ask(BufferedReader in, String label, String fallback) throws IOException {
        System.out.printf("%s [%s]: ", label, fallback);
        System.out.flush();
        String line = in.readLine();
        if (line == null) {
            System.out.println(fallback);
            return fallback;
        }
        String answer = line.trim();
        return answer.isEmpty() ? fallback : answer;
    }
}
