package io.jclaw.domain.prompt;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure context compaction: the view of a conversation a model request carries.
 *
 * <p>The loop state keeps every message of a run; the transcript keeps every message of a thread.
 * Neither is what a model should be sent once a thread outgrows its context window. This function
 * derives the request view from the full history under a {@link ContextPolicy}, so the truth
 * stays complete and only the <em>view</em> shrinks: replay and checkpoints are unaffected, and
 * a policy change alters what the model sees, never what was said.
 *
 * <p>Truncation keeps the most recent messages, and it keeps them structurally valid. Every
 * provider rejects a conversation that opens with a tool result, and most reject one that opens
 * with the assistant, so the kept window always starts at a <em>boundary</em>: a user message, or
 * an assistant message preceded by a synthetic user notice. A tool call and its results are never
 * separated, because the cut is chosen before the assistant message that requested them.
 *
 * <p>When anything is dropped, the model is told so. A short notice names how many messages were
 * omitted, folded into the first kept user message rather than injected as an extra turn, so the
 * shape of the conversation the provider sees is unchanged. Summarisation of the dropped span is
 * deliberately not attempted here: it needs a model call, which is an effect, and effects do not
 * belong in a pure function. A future compaction effect can hand a summary into this policy as
 * ordinary history.
 *
 * <p>The current message is always kept, whatever the budget says. Dropping the turn the user just
 * typed to satisfy a limit would be worse than an oversized request the provider rejects.
 */
public final class ContextCompaction {

    /** The estimate every provider-neutral budget in jclaw uses. */
    static final int CHARS_PER_TOKEN = 4;

    /** Per-message framing overhead in the estimate: role, delimiters, ids. */
    static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private ContextCompaction() {
    }

    /**
     * The compacted view.
     *
     * @param messages        what to send, oldest first, structurally valid
     * @param omittedMessages how many leading messages of the input were dropped
     * @param estimatedTokens estimated size of {@code messages}
     */
    public record Compacted(List<ChatMessage> messages, int omittedMessages, int estimatedTokens) {
        public Compacted {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (omittedMessages < 0 || estimatedTokens < 0) {
                throw new IllegalArgumentException("counts must be non-negative");
            }
        }

        public boolean compacted() {
            return omittedMessages > 0;
        }
    }

    /** Derives the request view of {@code messages} under {@code policy}. */
    public static Compacted compact(List<ChatMessage> messages, ContextPolicy policy) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(policy, "policy");
        int n = messages.size();
        if (n == 0) {
            return new Compacted(List.of(), 0, 0);
        }

        // Walk back from the newest message, accumulating cost, and remember the earliest
        // boundary whose suffix still fits. The first message that does not fit ends the walk:
        // nothing older can be kept without it.
        int tokens = 0;
        int count = 0;
        int cut = -1;
        for (int i = n - 1; i >= 0; i--) {
            tokens += estimateTokens(messages.get(i));
            count++;
            if (count > policy.maxMessages() || tokens > policy.maxInputTokens()) {
                break;
            }
            if (isBoundary(messages.get(i))) {
                cut = i;
            }
        }

        if (cut < 0) {
            // Even the newest boundary-aligned suffix exceeds the budget. Keep the smallest valid
            // one anyway: the current turn must reach the model.
            cut = lastBoundary(messages);
            if (cut < 0) {
                return new Compacted(messages, 0, estimateTokens(messages));
            }
        }
        if (cut == 0) {
            return new Compacted(messages, 0, estimateTokens(messages));
        }

        List<ChatMessage> kept = new ArrayList<>(messages.subList(cut, n));
        ContentBlock.Text notice = new ContentBlock.Text(notice(cut));
        ChatMessage first = kept.get(0);
        if (first.role() == ChatMessage.Role.USER) {
            List<ContentBlock> content = new ArrayList<>();
            content.add(notice);
            content.addAll(first.content());
            kept.set(0, new ChatMessage(ChatMessage.Role.USER, content));
        } else {
            kept.add(0, new ChatMessage(ChatMessage.Role.USER, List.of(notice)));
        }
        return new Compacted(kept, cut, estimateTokens(kept));
    }

    /** Estimated tokens for one message: characters over four, plus framing. */
    public static int estimateTokens(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        return message.weight() / CHARS_PER_TOKEN + MESSAGE_OVERHEAD_TOKENS;
    }

    /** Estimated tokens for a list of messages. */
    public static int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokens(message);
        }
        return total;
    }

    /**
     * Whether a kept window may start here.
     *
     * <p>A user message can open a conversation as is. An assistant message can open one behind
     * a synthetic user notice, and cutting there keeps any tool calls it makes together with the
     * results that follow. A tool-result message can never open one.
     */
    private static boolean isBoundary(ChatMessage message) {
        return message.role() == ChatMessage.Role.USER
                || message.role() == ChatMessage.Role.ASSISTANT;
    }

    private static int lastBoundary(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isBoundary(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The notice text. Stable wording, so a prompt-cache prefix survives across turns. */
    static String notice(int omitted) {
        return "[Context notice: " + omitted + " earlier message" + (omitted == 1 ? "" : "s")
                + " in this conversation " + (omitted == 1 ? "was" : "were")
                + " omitted to fit the context window. Continue from the messages that follow.]";
    }
}
