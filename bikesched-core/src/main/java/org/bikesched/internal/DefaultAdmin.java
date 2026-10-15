// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.bikesched.admin.Admin;
import org.bikesched.admin.EventNameSummary;
import org.bikesched.admin.EventQuery;
import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobData;
import org.bikesched.admin.JobQuery;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.JobTypeSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.RecurringJobRuns;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.internal.database.AdminStore;

/**
 * The {@link Admin} of a node: checks that the node is ready, then reads through the {@link
 * AdminStore}.
 */
final class DefaultAdmin implements Admin {

  // The digits of the short id the admin UI shows.
  private static final int MIN_ID_PREFIX = 8;

  private final AdminStore store;

  private final Duration nodeTimeout;

  private final Runnable ensureReady;

  // The job types with a handler on this node, a live view.
  private final Set<String> handledTypes;

  DefaultAdmin(
      AdminStore store, Duration nodeTimeout, Runnable ensureReady, Set<String> handledTypes) {
    this.store = Objects.requireNonNull(store, "store");
    this.nodeTimeout = Objects.requireNonNull(nodeTimeout, "nodeTimeout");
    this.ensureReady = Objects.requireNonNull(ensureReady, "ensureReady");
    this.handledTypes = Objects.requireNonNull(handledTypes, "handledTypes");
  }

  @Override
  public List<JobSummary> findJobs(JobQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return store.findJobs(query);
  }

  @Override
  public List<JobSummary> findJobsByIdPrefix(String idPrefix, int limit) {
    UUID[] range = idRange(idPrefix);
    requireLimit(limit);
    ensureReady.run();
    return store.findJobsByIdRange(range[0], range[1], limit);
  }

  @Override
  public RecurringJobRuns findRecurringJobRuns(String type, int limit) {
    Objects.requireNonNull(type, "type");
    requireLimit(limit);
    ensureReady.run();
    return store.findRecurringJobRuns(type, limit);
  }

  @Override
  public Optional<JobSummary> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady.run();
    return store.findJob(jobId);
  }

  @Override
  public Optional<JobSummary> findJob(String type, String idempotencyKey) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    ensureReady.run();
    return store.findJob(type, idempotencyKey);
  }

  @Override
  public Optional<JobData> findJobData(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady.run();
    return store.findJobData(jobId);
  }

  @Override
  public List<StepInfo> findSteps(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady.run();
    return store.findSteps(jobId);
  }

  @Override
  public List<JobTypeSummary> findJobTypes() {
    ensureReady.run();
    Set<String> handled = new TreeSet<>(handledTypes);
    List<JobTypeSummary> types = new ArrayList<>();
    for (JobTypeSummary type : store.findJobTypes()) {
      boolean isHandled = handled.remove(type.type());
      types.add(new JobTypeSummary(type.type(), type.counts(), type.recurrence(), isHandled));
    }
    if (!handled.isEmpty()) {
      handled.forEach(type -> types.add(new JobTypeSummary(type, Map.of(), null, true)));
      types.sort(Comparator.comparing(JobTypeSummary::type));
    }
    return types;
  }

  @Override
  public List<EventSummary> findEvents(EventQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return store.findEvents(query);
  }

  @Override
  public List<EventSummary> findEventsByIdPrefix(String idPrefix, int limit) {
    UUID[] range = idRange(idPrefix);
    requireLimit(limit);
    ensureReady.run();
    return store.findEventsByIdRange(range[0], range[1], limit);
  }

  @Override
  public Optional<EventSummary> findEvent(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId");
    ensureReady.run();
    return store.findEvent(eventId);
  }

  @Override
  public Optional<String> findEventPayload(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId");
    ensureReady.run();
    return store.findEventPayload(eventId);
  }

  @Override
  public List<EventNameSummary> findEventNames() {
    ensureReady.run();
    return store.findEventNames();
  }

  @Override
  public List<EventStep> findEventSteps(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId");
    ensureReady.run();
    return store.findEventSteps(eventId);
  }

  @Override
  public List<WaitingStep> findWaitingSteps(String eventName, String keyPrefix, int limit) {
    Objects.requireNonNull(eventName, "eventName");
    requireLimit(limit);
    ensureReady.run();
    return store.findWaitingSteps(eventName, keyPrefix, limit);
  }

  @Override
  public List<NodeInfo> findNodes() {
    ensureReady.run();
    return store.findNodes(nodeTimeout);
  }

  @Override
  public Optional<NodeInfo> findNode(UUID nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    ensureReady.run();
    return store.findNode(nodeId, nodeTimeout);
  }

  /**
   * The ids starting with the hex digits of the prefix: the prefix padded with {@code 0} and with
   * {@code f}. PostgreSQL orders {@code uuid} values by their bytes, the order of their hex digits,
   * so the range is read along the primary key.
   */
  private static UUID[] idRange(String idPrefix) {
    Objects.requireNonNull(idPrefix, "idPrefix");
    String hex = idPrefix.strip().replace("-", "").toLowerCase(Locale.ROOT);
    if (hex.length() < MIN_ID_PREFIX || hex.length() > 32 || !hex.matches("[0-9a-f]+")) {
      throw new IllegalArgumentException(
          "an id prefix must have between " + MIN_ID_PREFIX + " and 32 hex digits: " + idPrefix);
    }
    return new UUID[] {
      uuid(hex + "0".repeat(32 - hex.length())), uuid(hex + "f".repeat(32 - hex.length()))
    };
  }

  private static UUID uuid(String hex) {
    return new UUID(
        Long.parseUnsignedLong(hex.substring(0, 16), 16),
        Long.parseUnsignedLong(hex.substring(16), 16));
  }

  private static void requireLimit(int limit) {
    if (limit < 1 || limit > JobQuery.MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + JobQuery.MAX_LIMIT + ": " + limit);
    }
  }
}
