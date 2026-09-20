// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.capability.CapabilityId;
import io.jclaw.ports.loop.LoopHook;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import io.jclaw.ports.secret.SecretVault;
import io.jclaw.ports.secret.SecretVault.Binding;
import io.jclaw.ports.secret.SecretVault.SecretName;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnRunId;
import io.jclaw.ports.turn.TurnScope;
import io.jclaw.adapter.out.persistence.jsonl.JsonlFile;
import io.jclaw.adapter.out.persistence.secret.FileSecretVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last check before a request leaves: a credential that got into the transcript by any route
 * is rewritten back into its reference, and reasoning that cannot be rewritten stops the call.
 */
class SecretLeakHookTest {

    private static final String VALUE = "ghp_leaked_0123456789abcdef";

    @TempDir Path dir;

    private final LoopHook.HookContext context = new LoopHook.HookContext(
            new TurnRunId("run_1"), TurnScope.local("p", new ThreadId("t")), 0, 0.0);

    private SecretVault vault() {
        byte[] key = new byte[32];
        SecretVault vault = new FileSecretVault(new JsonlFile(dir.resolve("secrets.jsonl")), key, Clock.systemUTC());
        vault.put(new SecretName("gh"), VALUE,
                new Binding(CapabilityId.of("builtin.http_fetch"), Set.of("api.github.com")));
        return vault;
    }

    @SuppressWarnings("unchecked")
    private static ModelRequest proceeded(LoopHook.Outcome<ModelRequest> outcome) {
        return ((LoopHook.Outcome.Proceed<ModelRequest>)
                assertInstanceOf(LoopHook.Outcome.Proceed.class, outcome)).value();
    }

    private static ModelRequest request(ChatMessage... messages) {
        return ModelRequest.of("m", "you are jclaw", List.of(messages), 100);
    }

    @Test
    @DisplayName("a value a tool echoed back is rewritten to its reference, everywhere it appears")
    void redactsToolResults() {
        var hook = new SecretLeakHook(vault());
        ModelRequest incoming = request(
                ChatMessage.user("read the config"),
                new ChatMessage(ChatMessage.Role.USER, List.of(
                        ContentBlock.ToolResult.ok("c1", "token = " + VALUE),
                        new ContentBlock.Text("and again: " + VALUE))));

        var outcome = hook.beforeModel(context, incoming);
        ModelRequest sent = proceeded(outcome);
        String all = sent.messages().toString();
        assertFalse(all.contains(VALUE), "the value must not reach the provider");
        assertTrue(all.contains("{{secret:gh}}"), "the reference is what the model would have written");
        assertEquals(2, all.split("\\{\\{secret:gh}}", -1).length - 1, "both occurrences");
    }

    @Test
    @DisplayName("the system prompt and tool arguments are scanned too")
    void redactsPromptAndArguments() {
        var hook = new SecretLeakHook(vault());
        ModelRequest incoming = new ModelRequest(
                "m", "you are jclaw. the key is " + VALUE,
                List.of(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                        new ContentBlock.ToolUse("c1", "builtin.http_fetch",
                                Map.of("headers", Map.of("Authorization", "Bearer " + VALUE)))))),
                List.of(), 100, java.util.Optional.empty());

        ModelRequest sent = proceeded(hook.beforeModel(context, incoming));
        assertFalse(sent.system().contains(VALUE));
        assertTrue(sent.system().contains("{{secret:gh}}"));
        assertFalse(sent.messages().toString().contains(VALUE), "nested argument maps are walked");
    }

    @Test
    @DisplayName("a secret inside signed reasoning stops the call, because it cannot be rewritten")
    @SuppressWarnings("unchecked")
    void vetoesSignedReasoning() {
        var hook = new SecretLeakHook(vault());
        ModelRequest incoming = request(
                ChatMessage.user("go"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                        new ContentBlock.Thinking("I will use " + VALUE, "sig"))));

        var outcome = hook.beforeModel(context, incoming);
        var veto = (LoopHook.Outcome.Veto<ModelRequest>)
                assertInstanceOf(LoopHook.Outcome.Veto.class, outcome);
        assertTrue(veto.reason().contains("signed provider reasoning"));
    }

    @Test
    @DisplayName("a clean request is returned untouched, and an empty vault costs nothing")
    void passesCleanRequests() {
        var hook = new SecretLeakHook(vault());
        ModelRequest clean = request(ChatMessage.user("nothing sensitive here"));
        assertEquals(clean, proceeded(hook.beforeModel(context, clean)));

        var empty = new SecretLeakHook(SecretVault.empty());
        assertEquals(clean, proceeded(empty.beforeModel(context, clean)));
    }
}
