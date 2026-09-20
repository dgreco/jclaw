// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

/**
 * {@code ModelProvider} adapters: mock (scripted, golden-test default), Anthropic
 * Messages with SSE streaming, a generic OpenAI-compatible client, ollama, and the
 * failover chain with per-provider cooldown.
 *
 * <p>The only places the outside wire exists. HTTP via {@code java.net.http},
 * JSON via Jackson — every outbound URL passes the egress guard.
 */
package io.jclaw.adapter.out.model;
