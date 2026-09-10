// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;
import io.jclaw.contracts.secret.SecretVault;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * Manages the secret vault.
 *
 * <p>Values arrive on standard input or from the terminal, never as an argument: a command-line
 * argument is visible in the process list and lands in shell history. Listing shows names and
 * bindings only; there is no command that prints a value back.
 */
@Component
@Command(
        name = "secrets",
        description = "Store credentials the agent may use by reference, never see.",
        mixinStandardHelpOptions = true,
        subcommands = {SecretsCommand.Set.class, SecretsCommand.ListAll.class, SecretsCommand.Remove.class})
public class SecretsCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Component
    @Command(name = "set", description = "Store or replace a secret; the value is read from stdin or prompted.",
            mixinStandardHelpOptions = true)
    public static class Set implements Callable<Integer> {

        private final SecretVault vault;

        @Parameters(index = "0", description = "Secret name, as the model will reference it: {{secret:NAME}}.")
        private String name;

        @Option(names = "--capability", required = true,
                description = "The one capability the secret may be injected into, e.g. builtin.http_fetch.")
        private String capability;

        @Option(names = "--host", split = ",",
                description = "Hosts the capability may send it to (exact, or *.suffix). Comma-separated. "
                        + "Required unless --subprocess is given.")
        private String[] hosts = new String[0];

        @Option(names = "--subprocess",
                description = "Bind for a child process's environment instead of a host. The value is "
                        + "staged into the environment of a command the capability runs and never into "
                        + "an argument. There is no host rule, because a child process can reach "
                        + "anywhere: this is the operator saying that code may hold the credential.")
        private boolean subprocess;

        public Set(SecretVault vault) {
            this.vault = vault;
        }

        @Override
        public Integer call() throws IOException {
            String value = readValue();
            if (value == null || value.isEmpty()) {
                System.err.println("no value given: pipe it on stdin or type it at the prompt");
                return 1;
            }
            if (subprocess == (hosts.length > 0)) {
                System.err.println("jclaw: give either --host or --subprocess, not both and not neither");
                return 1;
            }
            // Qualified: this class declares a nested `Set` (the `secrets set` subcommand),
            // and a nested type shadows an import of the same simple name.
            java.util.Set<String> bound = subprocess
                    ? java.util.Set.of(Binding.SUBPROCESS)
                    : new LinkedHashSet<>(Arrays.asList(hosts));
            vault.put(new SecretName(name), value, new Binding(CapabilityId.of(capability), bound));
            System.out.println("stored " + name + " for " + capability
                    + (subprocess ? " as a subprocess environment variable"
                                  : " to " + String.join(", ", new TreeSet<>(bound))));
            return 0;
        }

        private static String readValue() throws IOException {
            Console console = System.console();
            if (console != null) {
                char[] typed = console.readPassword("value: ");
                return typed == null ? null : new String(typed);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null ? null : line.strip();
        }
    }

    @Component
    @Command(name = "list", description = "Show secret names and bindings. Values are never shown.",
            mixinStandardHelpOptions = true)
    public static class ListAll implements Callable<Integer> {

        private final SecretVault vault;

        public ListAll(SecretVault vault) {
            this.vault = vault;
        }

        @Override
        public Integer call() {
            var infos = vault.list();
            if (infos.isEmpty()) {
                System.out.println("(no secrets)");
                return 0;
            }
            for (SecretVault.SecretInfo info : infos) {
                java.util.Set<String> hosts = info.binding().hosts();
                String where = hosts.equals(java.util.Set.of(Binding.SUBPROCESS))
                        ? "(subprocess environment)"
                        : String.join(", ", new TreeSet<>(hosts));
                System.out.printf("%-24s %-24s %s  (%s)%n", info.name().value(),
                        info.binding().capability().value(), where, info.createdAt());
            }
            return 0;
        }
    }

    @Component
    @Command(name = "remove", description = "Delete a secret.", mixinStandardHelpOptions = true)
    public static class Remove implements Callable<Integer> {

        private final SecretVault vault;

        @Parameters(index = "0", description = "Secret name.")
        private String name;

        public Remove(SecretVault vault) {
            this.vault = vault;
        }

        @Override
        public Integer call() {
            if (vault.remove(new SecretName(name))) {
                System.out.println("removed " + name);
                return 0;
            }
            System.err.println("no such secret: " + name);
            return 1;
        }
    }
}
