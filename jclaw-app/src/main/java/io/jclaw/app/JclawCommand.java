// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app;

import io.jclaw.app.cli.ApprovalsCommand;
import io.jclaw.app.cli.DoctorCommand;
import io.jclaw.app.cli.ExtensionsCommand;
import io.jclaw.app.cli.McpCommand;
import io.jclaw.app.cli.MemoryCommand;
import io.jclaw.app.cli.ModelsCommand;
import io.jclaw.app.cli.OnboardCommand;
import io.jclaw.app.cli.RecoverCommand;
import io.jclaw.app.cli.RetainCommand;
import io.jclaw.app.cli.ReplCommand;
import io.jclaw.app.cli.ResumeCommand;
import io.jclaw.app.cli.RoutinesCommand;
import io.jclaw.app.cli.RunCommand;
import io.jclaw.app.cli.InboundCommand;
import io.jclaw.app.cli.SecretsCommand;
import io.jclaw.app.cli.ServeCommand;
import io.jclaw.app.cli.SubmitCommand;
import io.jclaw.app.cli.SkillsCommand;
import io.jclaw.app.cli.StatusCommand;
import io.jclaw.app.cli.ToolsCommand;
import io.jclaw.app.cli.WorkerCommand;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * Root CLI command.
 *
 * <p>A container only — it owns no behaviour, so each subcommand stays independently testable and
 * the root does not become the place logic accumulates.
 */
@Component
@Command(
        name = "jclaw",
        description = "jclaw - a hexagonal agent OS harness.",
        version = "jclaw 0.1.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        // These are consumed before picocli parses argv (JclawApplication expands --debug/--trace
        // and strips the Spring namespaces), so no @Option exists for them. Documenting them here
        // is what keeps them discoverable — an option that works everywhere but appears nowhere
        // in --help may as well not exist.
        footerHeading = "%n",
        footer = {
                "Global options (accepted by every command):",
                "  --debug                        Narrate every pipeline step at DEBUG.",
                "  --trace                        DEBUG plus payloads: prompts, messages, tool",
                "                                 arguments, results (redacted and bounded).",
                "",
                "Configuration overrides (also settable as JCLAW_* environment variables",
                "or in ~/.jclaw/jclaw.yaml, written by 'jclaw onboard'):",
                "  --jclaw.provider=<id>          mock | anthropic | openai | openrouter |",
                "                                 ollama | local | failover  (default: mock)",
                "  --jclaw.model=<id>             Model id passed to the provider.",
                "  --jclaw.workspace=<dir>        Root the agent may read and write (default: .)",
                "  --jclaw.state-dir=<dir>        Transcripts, event log, approvals",
                "                                 (default: ~/.jclaw)",
                "  --jclaw.approval-mode=<mode>   read-only | interactive | trusted",
                "  --jclaw.local-base-url=<url>   OpenAI-compatible server for provider 'local',",
                "                                 e.g. http://localhost:1234/v1 (LM Studio) or",
                "                                 http://localhost:8000/v1 (vLLM).",
                "  --jclaw.openai-base-url=<url>  Endpoint for the 'openai' provider.",
                "  --jclaw.ollama-base-url=<url>  Ollama daemon (default: localhost:11434/v1).",
                "  --jclaw.openrouter-base-url=<url>",
                "                                 OpenRouter endpoint override.",
                "  --jclaw.openrouter-referer=<url>",
                "                                 Optional HTTP-Referer attribution header.",
                "  --jclaw.openrouter-title=<text>",
                "                                 Optional X-Title attribution header.",
                "  --jclaw.max-iterations=<n>     Tick-cycle cap per run (default: 25).",
                "  --jclaw.max-tokens=<n>         Token budget per run, 0 for unlimited",
                "                                 (default: 500000).",
                "  --jclaw.allow-private-networks=<bool>",
                "                                 Let tools reach loopback and RFC1918 addresses.",
                "                                 Development only: re-opens the SSRF surface.",
                "  --jclaw.system-prompt=<text>   System prompt prepended to every turn.",
                "  --jclaw.mock-script[<n>]=<step>",
                "                                 Scripted turns for the mock provider, in order:",
                "                                 text:<reply> or tool:<capability>:<k=v,k=v>",
                "  --logging.level.<logger>=<level>",
                "                                 Raw log-level control, e.g.",
                "                                 --logging.level.io.jclaw=TRACE",
                "",
                "Provider credentials come from the environment, never from flags or config files:",
                "  ANTHROPIC_API_KEY, OPENAI_API_KEY, OPENROUTER_API_KEY,",
                "  LOCAL_API_KEY (optional; sent only if the local server checks a token).",
                "Credentials for tools go in the vault: jclaw secrets set NAME --capability ... --host ..."
        },
        subcommands = {
                RunCommand.class,
                SubmitCommand.class,
                ReplCommand.class,
                ApprovalsCommand.class,
                ResumeCommand.class,
                MemoryCommand.class,
                RoutinesCommand.class,
                SkillsCommand.class,
                ExtensionsCommand.class,
                McpCommand.class,
                ModelsCommand.class,
                OnboardCommand.class,
                ToolsCommand.class,
                StatusCommand.class,
                RecoverCommand.class,
                RetainCommand.class,
                SecretsCommand.class,
                InboundCommand.class,
                WorkerCommand.class,
                ServeCommand.class,
                DoctorCommand.class
        })
public class JclawCommand implements Runnable {

    /** Bare {@code jclaw} prints usage. Exit code 0 — asking for help is not an error. */
    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }
}
