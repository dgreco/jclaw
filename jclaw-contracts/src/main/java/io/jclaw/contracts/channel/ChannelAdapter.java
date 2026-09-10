// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.channel;

import io.jclaw.contracts.Result;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A messaging platform jclaw can be talked to from: Slack, Telegram, anything with a webhook and
 * a send API.
 *
 * <p>An adapter does two things and knows nothing else. It turns a webhook request into an
 * {@link Inbound}, having first proved the request really came from the platform, and it sends a
 * reply to a {@link ReplyTarget}. Everything between those two points is the ordinary runtime:
 * the message is enqueued as a turn, the scheduler runs it, gates park it, and the reply is
 * dispatched when the run finishes.
 *
 * <p>Adapters hold no credentials. The application layer leases them from the vault and passes
 * them in, exactly as it does for an MCP server's bearer token, so a platform token is never in
 * a config file and never below the application layer.
 */
public interface ChannelAdapter {

    /** Stable id, used in routes, reply targets, and configuration. */
    String id();

    /** The host this adapter sends to, checked against the egress guard before a call. */
    String apiHost();

    /** A message that arrived from the platform. */
    record Inbound(String text, ReplyTarget replyTo, String author) {
        public Inbound {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(replyTo, "replyTo");
            Objects.requireNonNull(author, "author");
        }
    }

    /**
     * What a webhook request turned out to be.
     *
     * <p>Three outcomes, and they are different on purpose. A platform sends far more than user
     * messages: retries, edits, reactions, its own bot's posts, and setup handshakes. Treating
     * "nothing for us" as an error would fill the log with noise and, worse, tempt a caller into
     * answering things nobody said.
     */
    sealed interface Delivery {
        /** A real message to run a turn for. */
        record Message(Inbound inbound) implements Delivery { }

        /** Understood, and deliberately nothing to do. */
        record Ignored(String why) implements Delivery { }

        /** The platform is verifying the endpoint; answer with this body and do nothing else. */
        record Handshake(String responseBody) implements Delivery { }
    }

    /**
     * Verifies and decodes one webhook request.
     *
     * @param headers      request headers, lower-cased keys
     * @param body         the raw request body, exactly as received, since signatures cover bytes
     * @param verifySecret the platform's signing or verification secret, leased from the vault
     * @return what the request was, or a stable reason it was refused
     */
    Result<Delivery, String> receive(Map<String, String> headers, String body, String verifySecret);

    /**
     * Sends a reply.
     *
     * <p>Returns the platform's identifier for the sent message where it gives one, so success
     * carries something rather than nothing. {@code Result} holds no nulls, and a {@code Void}
     * success would have to invent one.
     *
     * @param token the platform API credential, leased from the vault
     */
    Result<String, String> send(ReplyTarget target, String text, String token);

    /** The endpoint this adapter posts to for {@code target}. Exposed so the host can guard it. */
    Optional<String> sendUrl(ReplyTarget target);
}
