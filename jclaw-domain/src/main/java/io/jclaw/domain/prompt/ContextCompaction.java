package io.jclaw.domain.prompt;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Compaction is applied twice in a run's life, at admission to bound the seed and by the
 * machine before every model call, so it is <b>idempotent</b>: it recognises its own notice, does
 * not count a synthetic notice against the message cap, and when a later pass drops a notice it
 * carries that notice's count forward. The model always sees the total omitted since the start of
 * the thread, never just the last pass.
 *
 * <p>The current message is always kept, whatever the budget says. Dropping the turn the user just
 * typed to satisfy a limit would be worse than an oversized request the provider rejects.
 */
public final class ContextCompaction {

    /** The estimate every provider-neutral budget in jclaw uses. */
    static final int CHARS_PER_TOKEN = 4;

    /** Per-message framing overhead in the estimate: role, delimiters, ids. */
    static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private static final String NOTICE_PREFIX = "[Context notice: ";

    private static final Pattern NOTICE = Pattern.compile(
            "^\\[Context notice: (\\d+) earlier message");

    private ContextCompaction() {
    }

    /**
     * The compacted view.
     *
     * @param messages        what to send, oldest first, structurally valid
     * @param omittedMessages how many real messages of the thread are not in the view, including
     *                        those dropped by earlier passes
     * @param estimatedTokens estimated size of {@code messages}
     * @param dropped         the messages this pass removed, in order, including any notice from
     *                        an earlier pass; what a summariser is handed
     */
    public record Compacted(
            List<ChatMessage> messages, int omittedMessages, int estimatedTokens, List<ChatMessage> dropped) {

        public Compacted {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            dropped = List.copyOf(Objects.requireNonNull(dropped, "dropped"));
            if (omittedMessages < 0 || estimatedTokens < 0) {
                throw new IllegalArgumentException("counts must be non-negative");
            }
        }

        public Compacted(List<ChatMessage> messages, int omittedMessages, int estimatedTokens) {
            this(messages, omittedMessages, estimatedTokens, List.of());
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
            ChatMessage message = messages.get(i);
            tokens += estimateTokens(message);
            if (!isSyntheticNotice(message)) {
                count++;
            }
            if (count > policy.maxMessages() || tokens > policy.maxInputTokens()) {
                break;
            }
            if (isBoundary(message)) {
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
            return new Compacted(messages, priorOmitted(messages.get(0)), estimateTokens(messages));
        }

        // Real messages dropped in this pass, plus whatever an earlier pass already reported.
        int omitted = 0;
        for (int i = 0; i < cut; i++) {
            ChatMessage dropped = messages.get(i);
            omitted += priorOmitted(dropped);
            if (!isSyntheticNotice(dropped)) {
                omitted++;
            }
        }

        List<ChatMessage> kept = withNotice(messages.subList(cut, n), notice(omitted));
        return new Compacted(kept, omitted, estimateTokens(kept), messages.subList(0, cut));
    }

    /**
     * Places a notice at the front of a window: folded into the first message when it is a user
     * message (replacing any notice already there rather than stacking one), or as a synthetic
     * user message when the window opens with the assistant.
     */
    static List<ChatMessage> withNotice(List<ChatMessage> window, String noticeText) {
        List<ChatMessage> kept = new ArrayList<>(window);
        ContentBlock.Text notice = new ContentBlock.Text(noticeText);
        if (kept.isEmpty()) {
            kept.add(new ChatMessage(ChatMessage.Role.USER, List.of(notice)));
            return List.copyOf(kept);
        }
        ChatMessage first = kept.get(0);
        if (first.role() == ChatMessage.Role.USER) {
            List<ContentBlock> content = new ArrayList<>();
            content.add(notice);
            content.addAll(carriesNotice(first)
                    ? first.content().subList(1, first.content().size())
                    : first.content());
            kept.set(0, new ChatMessage(ChatMessage.Role.USER, content));
        } else {
            kept.add(0, new ChatMessage(ChatMessage.Role.USER, List.of(notice)));
        }
        return List.copyOf(kept);
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

    /** A user message whose first block is a notice from an earlier pass. */
    private static boolean carriesNotice(ChatMessage message) {
        return message.role() == ChatMessage.Role.USER
                && !message.content().isEmpty()
                && message.content().get(0) instanceof ContentBlock.Text text
                && text.text().startsWith(NOTICE_PREFIX);
    }

    /** A message that is <em>only</em> a notice: synthetic, not a real turn, free of charge. */
    private static boolean isSyntheticNotice(ChatMessage message) {
        return carriesNotice(message) && message.content().size() == 1;
    }

    /** The count an earlier pass reported in this message's notice, or zero. */
    static int priorOmitted(ChatMessage message) {
        if (!carriesNotice(message)) {
            return 0;
        }
        Matcher matcher = NOTICE.matcher(((ContentBlock.Text) message.content().get(0)).text());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** The notice text. Stable wording, so a prompt-cache prefix survives across turns. */
    static String notice(int omitted) {
        return NOTICE_PREFIX + omitted + " earlier message" + (omitted == 1 ? "" : "s")
                + " in this conversation " + (omitted == 1 ? "was" : "were")
                + " omitted to fit the context window. Continue from the messages that follow.]";
    }
}
