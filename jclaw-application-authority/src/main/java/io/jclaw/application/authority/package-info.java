// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

/**
 * Capability kernel: registry, gate evaluation, tool policy, leak engine,
 * vault logic, audit policy, skill catalog, routine policy.
 *
 * <p>Policies here are decided over plain values — the loop consults them
 * inside the pure machine, so denials never depend on an adapter having run.
 */
package io.jclaw.application.authority;
