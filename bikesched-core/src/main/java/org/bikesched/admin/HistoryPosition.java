// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.history.HistoryEntry;

/**
 * A position in the history's order ({@link HistoryEntry#createdAt()}, then {@link
 * HistoryEntry#id()}), where the next page of {@link Admin#findHistory(HistoryQuery)} starts.
 *
 * @param createdAt the time of the entry at the position
 * @param id the id of the entry at the position
 */
public record HistoryPosition(Instant createdAt, UUID id) {

  /** Checks the position is complete. */
  public HistoryPosition {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(id, "id");
  }

  /**
   * Returns the position of an entry.
   *
   * @param entry the entry
   * @return its position
   */
  public static HistoryPosition of(HistoryEntry entry) {
    return new HistoryPosition(entry.createdAt(), entry.id());
  }
}
