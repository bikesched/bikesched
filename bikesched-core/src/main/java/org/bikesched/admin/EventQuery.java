// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Filters and paging of {@link Admin#findEvents(EventQuery)}, keyset based like {@link JobQuery}.
 *
 * @param name only events of this name; {@code null} for every name
 * @param keyPrefix only events whose key starts with it; {@code null} or empty for every key
 * @param publishedBefore with {@code beforeId}: only events after this position in the order;
 *     {@code null} for the first page
 * @param beforeId the id of the last event of the previous page; {@code null} for the first page
 * @param limit the maximum number of events, between 1 and {@link JobQuery#MAX_LIMIT}
 */
public record EventQuery(
    String name, String keyPrefix, Instant publishedBefore, UUID beforeId, int limit) {

  /**
   * Validates the query.
   *
   * @throws IllegalArgumentException if the limit is out of range or only one of {@code
   *     publishedBefore} and {@code beforeId} is set
   */
  public EventQuery {
    JobQuery.requireLimit(limit);
    if ((publishedBefore == null) != (beforeId == null)) {
      throw new IllegalArgumentException("publishedBefore and beforeId must be set together");
    }
    if (keyPrefix != null && keyPrefix.isEmpty()) {
      keyPrefix = null;
    }
  }

  /**
   * Returns the first page of every unexpired event, newest first.
   *
   * @param limit the maximum number of events, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the query
   */
  public static EventQuery newest(int limit) {
    return new EventQuery(null, null, null, null, limit);
  }

  /**
   * Returns this query restricted to one event name.
   *
   * @param name the event name; {@code null} for every name
   * @return a new query
   */
  public EventQuery withName(String name) {
    return new EventQuery(name, keyPrefix, publishedBefore, beforeId, limit);
  }

  /**
   * Returns this query restricted to keys starting with the prefix.
   *
   * @param keyPrefix the prefix; {@code null} or empty for every key
   * @return a new query
   */
  public EventQuery withKeyPrefix(String keyPrefix) {
    return new EventQuery(name, keyPrefix, publishedBefore, beforeId, limit);
  }

  /**
   * Returns the page after the given event, the last one of the previous page.
   *
   * @param last the last event of the previous page
   * @return a new query
   */
  public EventQuery after(EventSummary last) {
    Objects.requireNonNull(last, "last");
    return new EventQuery(name, keyPrefix, last.publishedAt(), last.id(), limit);
  }
}
