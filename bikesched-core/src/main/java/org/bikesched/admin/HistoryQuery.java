// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bikesched.history.ActorType;
import org.bikesched.history.HistoryAction;
import org.bikesched.history.HistoryEntry;
import org.jspecify.annotations.Nullable;

/**
 * Filters and paging of {@link Admin#findHistory(HistoryQuery)}, keyset based on the history's
 * order: the next page starts after the position the previous one returned ({@link
 * HistoryPage#next()}).
 *
 * <pre>{@code
 * HistoryQuery query = HistoryQuery.newest(50).withActorType(ActorType.USER);
 * HistoryPage page = admin.findHistory(query);
 * if (page.next() != null) {
 *   HistoryPage older = admin.findHistory(query.after(page.next()));
 * }
 * }</pre>
 *
 * @param entryType only entries of this type ({@code JobEntry.class}, {@code StepEntry.class},
 *     {@code EventEntry.class}, {@code NodeEntry.class}); {@code null} for every type
 * @param actorType only entries of this actor type; {@code null} for every actor
 * @param actions only entries with one of these actions; empty for every action
 * @param causedById only entries caused by this event, job or node ({@link
 *     HistoryEntry#causedById()}); {@code null} for every entry
 * @param since only entries created at or after this time; {@code null} for no lower bound
 * @param until only entries created before this time; {@code null} for no upper bound
 * @param after only entries after this position in the order, i.e. older: where the previous page
 *     ended; {@code null} for the first page
 * @param limit the maximum number of entries, between 1 and {@link JobQuery#MAX_LIMIT}
 */
public record HistoryQuery(
    @Nullable Class<? extends HistoryEntry> entryType,
    @Nullable ActorType actorType,
    Set<HistoryAction> actions,
    @Nullable UUID causedById,
    @Nullable Instant since,
    @Nullable Instant until,
    @Nullable HistoryPosition after,
    int limit) {

  /**
   * How many entries {@link Admin#findHistory(HistoryQuery)} scans at most for a page with filters
   * that follow no index (entry type, actions, actor types other than users).
   */
  public static final int SCAN_LIMIT = 5000;

  /**
   * Validates the query and copies the actions.
   *
   * @throws IllegalArgumentException if the limit is out of range or the entry type is not one of
   *     the entry records
   */
  public HistoryQuery {
    actions = actions == null ? Set.of() : Set.copyOf(actions);
    JobQuery.requireLimit(limit);
    if (entryType != null && !entryType.isRecord()) {
      throw new IllegalArgumentException("Not a type of history entry: " + entryType.getName());
    }
  }

  /**
   * Returns the first page of every entry, newest first.
   *
   * @param limit the maximum number of entries, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the query
   */
  public static HistoryQuery newest(int limit) {
    return new HistoryQuery(null, null, Set.of(), null, null, null, null, limit);
  }

  /**
   * Returns this query restricted to one type of entry.
   *
   * @param entryType e.g. {@code StepEntry.class}; {@code null} for every type
   * @return a new query
   */
  public HistoryQuery withEntryType(@Nullable Class<? extends HistoryEntry> entryType) {
    return new HistoryQuery(entryType, actorType, actions, causedById, since, until, after, limit);
  }

  /**
   * Returns this query restricted to one actor type, e.g. the actions of users.
   *
   * @param actorType the actor type; {@code null} for every actor
   * @return a new query
   */
  public HistoryQuery withActorType(@Nullable ActorType actorType) {
    return new HistoryQuery(entryType, actorType, actions, causedById, since, until, after, limit);
  }

  /**
   * Returns this query restricted to the given actions, of one entry type or several.
   *
   * @param actions the actions; none for every action
   * @return a new query
   */
  public HistoryQuery withActions(HistoryAction... actions) {
    return withActions(Set.of(actions));
  }

  /**
   * Returns this query restricted to the given actions, of one entry type or several.
   *
   * @param actions the actions; empty for every action
   * @return a new query
   */
  public HistoryQuery withActions(Set<? extends HistoryAction> actions) {
    return new HistoryQuery(
        entryType, actorType, Set.copyOf(actions), causedById, since, until, after, limit);
  }

  /**
   * Returns this query restricted to the entries an event, job or node caused.
   *
   * @param causedById the id of the event, job or node; {@code null} for every entry
   * @return a new query
   */
  public HistoryQuery withCausedBy(@Nullable UUID causedById) {
    return new HistoryQuery(entryType, actorType, actions, causedById, since, until, after, limit);
  }

  /**
   * Returns this query restricted to the entries created at or after a time.
   *
   * @param since the lower bound, inclusive; {@code null} for none
   * @return a new query
   */
  public HistoryQuery withSince(@Nullable Instant since) {
    return new HistoryQuery(entryType, actorType, actions, causedById, since, until, after, limit);
  }

  /**
   * Returns this query restricted to the entries created before a time.
   *
   * @param until the upper bound, exclusive; {@code null} for none
   * @return a new query
   */
  public HistoryQuery withUntil(@Nullable Instant until) {
    return new HistoryQuery(entryType, actorType, actions, causedById, since, until, after, limit);
  }

  /**
   * Returns the page after a position, where the previous page ended ({@link HistoryPage#next()}).
   *
   * @param position the position
   * @return a new query
   */
  public HistoryQuery after(HistoryPosition position) {
    Objects.requireNonNull(position, "position");
    return new HistoryQuery(
        entryType, actorType, actions, causedById, since, until, position, limit);
  }
}
