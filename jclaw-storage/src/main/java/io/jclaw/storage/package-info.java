// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

/**
 * Durable state: append-only JSONL event, audit, and checkpoint stores;
 * JdbcClient repositories over the H2 default store; migrations; FTS posting
 * lists and the vector index SPI.
 *
 * <p>Events are the authority; SQL tables are read-time projections. Dialect
 * differences (limit, full-text, vector match) live behind {@code SqlDialect}
 * so a Postgres+pgvector profile drops in without touching repositories.
 */
package io.jclaw.storage;
