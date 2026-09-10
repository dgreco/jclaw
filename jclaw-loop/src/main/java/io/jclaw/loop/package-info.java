// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

/**
 * The driver side of the hexagon: {@code DefaultAgentLoopDriver}, the turn runner,
 * the single effect interpreter, and checkpoint bookkeeping.
 *
 * <p>This module talks to ports only. It must never import providers, tools,
 * or storage — the dependency-law ArchUnit rule that keeps adapters pluggable.
 */
package io.jclaw.loop;
