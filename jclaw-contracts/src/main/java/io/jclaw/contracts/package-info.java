/**
 * Ports and wire records: the hexagonal boundary vocabulary.
 *
 * <p>Dependency law (enforced by ArchUnit in jclaw-app): no Spring, no Jackson databind
 * ({@code ObjectMapper} is an adapter detail), no {@code java.sql}, no HTTP.
 */
package io.jclaw.contracts;
