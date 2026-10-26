// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bikesched.history.HistoryEntry;
import org.jspecify.annotations.Nullable;

/**
 * A page of {@link Admin#findHistory(HistoryQuery)}.
 *
 * @param entries the matching entries, newest first
 * @param next where the next page starts ({@link HistoryQuery#after(HistoryPosition)}); {@code
 *     null} if there are no more entries. A page with fewer entries than its limit can still have a
 *     next one, if it stopped after scanning its maximum number of entries.
 * @param subjects the nodes that wrote the entries ({@link HistoryEntry#nodeId()}) and the subjects
 *     that caused them ({@link HistoryEntry#causedById()}), by id, with their names; a subject that
 *     is neither registered (a node) nor in the history any more is left out
 */
public record HistoryPage(
    List<HistoryEntry> entries,
    @Nullable HistoryPosition next,
    Map<UUID, HistorySubject> subjects) {

  /** Copies the entries and subjects. */
  public HistoryPage {
    entries = List.copyOf(entries);
    subjects = Map.copyOf(subjects);
  }
}
