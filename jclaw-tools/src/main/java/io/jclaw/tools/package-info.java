// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

/**
 * Built-in tool suite and host adapters: shell, file, glob/grep, webfetch,
 * memory and skill tools — each declaring a per-call {@code ToolEffect}.
 *
 * <p>No adapter here may hold a {@code SecretVault} handle: credentials are minted
 * at the loop host boundary for provider use only, and tool results travel fenced.
 */
package io.jclaw.tools;
