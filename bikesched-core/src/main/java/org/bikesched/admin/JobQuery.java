// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * Filters and paging of {@link Admin#findJobs(JobQuery)}. Pages are keyset based: the next page
 * starts after the last job of the previous one ({@link #after(JobSummary)}).
 *
 * <pre>{@code
 * JobQuery query = JobQuery.newest(50).withType("send-email").withStates(JobState.FAILED);
 * List<JobSummary> page = admin.findJobs(query);
 * List<JobSummary> next = admin.findJobs(query.after(page.getLast()));
 * }</pre>
 *
 * @param type only jobs of this type; {@code null} for every type
 * @param states only jobs in one of these states; empty for every state
 * @param createdBefore with {@code beforeId}: only jobs after this position in the order (created
 *     earlier, or at the same time with a smaller id); {@code null} for the first page
 * @param beforeId the id of the last job of the previous page; {@code null} for the first page
 * @param limit the maximum number of jobs, between 1 and {@link #MAX_LIMIT}
 */
public record JobQuery(
    @Nullable String type,
    Set<JobState> states,
    @Nullable Instant createdBefore,
    @Nullable UUID beforeId,
    int limit) {

  /** The largest page size. */
  public static final int MAX_LIMIT = 500;

  /**
   * Validates the query and copies the states.
   *
   * @throws IllegalArgumentException if the limit is out of range or only one of {@code
   *     createdBefore} and {@code beforeId} is set
   */
  public JobQuery {
    states = states == null || states.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(states));
    requireLimit(limit);
    if ((createdBefore == null) != (beforeId == null)) {
      throw new IllegalArgumentException("createdBefore and beforeId must be set together");
    }
  }

  /**
   * Returns the first page of every job, newest first.
   *
   * @param limit the maximum number of jobs, between 1 and {@link #MAX_LIMIT}
   * @return the query
   */
  public static JobQuery newest(int limit) {
    return new JobQuery(null, Set.of(), null, null, limit);
  }

  /**
   * Returns this query restricted to one job type.
   *
   * @param type the job type; {@code null} for every type
   * @return a new query
   */
  public JobQuery withType(@Nullable String type) {
    return new JobQuery(type, states, createdBefore, beforeId, limit);
  }

  /**
   * Returns this query restricted to the given states.
   *
   * @param states the states; none for every state
   * @return a new query
   */
  public JobQuery withStates(JobState... states) {
    return withStates(states.length == 0 ? Set.of() : EnumSet.of(states[0], states));
  }

  /**
   * Returns this query restricted to the given states.
   *
   * @param states the states; empty for every state
   * @return a new query
   */
  public JobQuery withStates(Set<JobState> states) {
    return new JobQuery(type, states, createdBefore, beforeId, limit);
  }

  /**
   * Returns the page after the given job, the last one of the previous page.
   *
   * @param last the last job of the previous page
   * @return a new query
   */
  public JobQuery after(JobSummary last) {
    Objects.requireNonNull(last, "last");
    return new JobQuery(type, states, last.createdAt(), last.id(), limit);
  }

  static void requireLimit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_LIMIT + ", was " + limit);
    }
  }
}
