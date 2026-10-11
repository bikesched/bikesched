// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.internal.database.AdminStore;

/**
 * The {@link Admin} of a node: checks that the node is ready, then reads through the {@link
 * AdminStore}.
 */
final class DefaultAdmin implements Admin {

  private final AdminStore store;

  private final Duration nodeTimeout;

  private final Runnable ensureReady;

  DefaultAdmin(AdminStore store, Duration nodeTimeout, Runnable ensureReady) {
    this.store = Objects.requireNonNull(store, "store");
    this.nodeTimeout = Objects.requireNonNull(nodeTimeout, "nodeTimeout");
    this.ensureReady = Objects.requireNonNull(ensureReady, "ensureReady");
  }

  @Override
  public List<JobSummary> findJobs(JobQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return store.findJobs(query);
  }

  @Override
  public Optional<JobSummary> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady.run();
    return store.findJob(jobId);
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
    return store.findJobTypes();
  }

  @Override
  public List<EventSummary> findEvents(EventQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return store.findEvents(query);
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
    if (limit < 1 || limit > JobQuery.MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + JobQuery.MAX_LIMIT + ": " + limit);
    }
    ensureReady.run();
    return store.findWaitingSteps(eventName, keyPrefix, limit);
  }

  @Override
  public List<NodeInfo> findNodes() {
    ensureReady.run();
    return store.findNodes(nodeTimeout);
  }
}
