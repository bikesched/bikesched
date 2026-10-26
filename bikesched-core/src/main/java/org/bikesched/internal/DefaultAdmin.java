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
import org.bikesched.admin.CancelJobBuilder;
import org.bikesched.admin.EventNameSummary;
import org.bikesched.admin.EventQuery;
import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.HistoryPage;
import org.bikesched.admin.HistoryQuery;
import org.bikesched.admin.JobData;
import org.bikesched.admin.JobNameSummary;
import org.bikesched.admin.JobQuery;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.RecurringJobRuns;
import org.bikesched.admin.RemoveRecurringJobBuilder;
import org.bikesched.admin.RetryJobBuilder;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.history.Actor;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.SubjectType;
import org.bikesched.internal.database.AdminStore;
import org.bikesched.internal.database.HistoryStore;
import org.bikesched.internal.util.ValidationUtil;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Admin} of a node: checks that the node is ready, then reads through the {@link
 * AdminStore}; its actions run through the node's {@link Actions}.
 */
final class DefaultAdmin implements Admin {

  /** The actions of the owning node, with the user acting ({@code null} for the application). */
  interface Actions {
    boolean cancel(UUID jobId, @Nullable String message, @Nullable Actor actor);

    boolean retry(UUID jobId, @Nullable Actor actor);

    boolean removeRecurringJob(String name, @Nullable Actor actor);
  }

  // The digits of the short id the admin UI shows.
  private static final int MIN_ID_SUFFIX = 8;

  private final AdminStore store;

  private final HistoryStore history;

  private final Duration nodeTimeout;

  private final Runnable ensureReady;

  private final Actions actions;

  // The job names with a handler on this node, a live view.
  private final Set<String> handledNames;

  DefaultAdmin(
      AdminStore store,
      HistoryStore history,
      Duration nodeTimeout,
      Runnable ensureReady,
      Actions actions,
      Set<String> handledNames) {
    this.store = store;
    this.history = history;
    this.nodeTimeout = nodeTimeout;
    this.ensureReady = ensureReady;
    this.actions = actions;
    this.handledNames = handledNames;
  }

  @Override
  public List<JobSummary> findJobs(JobQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return store.findJobs(query);
  }

  @Override
  public List<JobSummary> findJobsByIdSuffix(String idSuffix, int limit) {
    String suffix = idSuffix(idSuffix);
    requireLimit(limit);
    ensureReady.run();
    return store.findJobsByIdSuffix(suffix, limit);
  }

  @Override
  public RecurringJobRuns findRecurringJobRuns(String name, int limit) {
    Objects.requireNonNull(name, "name");
    requireLimit(limit);
    ensureReady.run();
    return store.findRecurringJobRuns(name, limit);
  }

  @Override
  public Optional<JobSummary> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady.run();
    return store.findJob(jobId);
  }

  @Override
  public Optional<JobSummary> findJob(String name, String idempotencyKey) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    ensureReady.run();
    return store.findJob(name, idempotencyKey);
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
  public Optional<StepInfo> findStep(UUID stepId) {
    Objects.requireNonNull(stepId, "stepId");
    ensureReady.run();
    return store.findStep(stepId);
  }

  @Override
  public Optional<String> findStepResult(UUID stepId) {
    Objects.requireNonNull(stepId, "stepId");
    ensureReady.run();
    return store.findStepResult(stepId);
  }

  @Override
  public List<JobNameSummary> findJobNames() {
    ensureReady.run();
    Set<String> handled = new TreeSet<>(handledNames);
    List<JobNameSummary> names = new ArrayList<>();
    for (JobNameSummary name : store.findJobNames()) {
      boolean isHandled = handled.remove(name.name());
      names.add(new JobNameSummary(name.name(), name.counts(), name.recurrence(), isHandled));
    }
    if (!handled.isEmpty()) {
      handled.forEach(name -> names.add(new JobNameSummary(name, Map.of(), null, true)));
      names.sort(Comparator.comparing(JobNameSummary::name));
    }
    return names;
  }

  @Override
  public List<EventSummary> findEvents(EventQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return store.findEvents(query);
  }

  @Override
  public List<EventSummary> findEventsByIdSuffix(String idSuffix, int limit) {
    String suffix = idSuffix(idSuffix);
    requireLimit(limit);
    ensureReady.run();
    return store.findEventsByIdSuffix(suffix, limit);
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
  public List<WaitingStep> findWaitingSteps(
      String eventName, @Nullable String keyPrefix, int limit) {
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

  @Override
  public List<HistoryEntry> findNodeHistory(UUID nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    ensureReady.run();
    return history.findHistoryWithCaused(SubjectType.NODE, nodeId);
  }

  @Override
  public HistoryPage findHistory(HistoryQuery query) {
    Objects.requireNonNull(query, "query");
    ensureReady.run();
    return history.findHistory(query, HistoryQuery.SCAN_LIMIT);
  }

  @Override
  public boolean isRecordingHistory() {
    return history.isEnabled();
  }

  // The hex digits of an id suffix, lower case and without hyphens.
  private static String idSuffix(String idSuffix) {
    Objects.requireNonNull(idSuffix, "idSuffix");
    String hex = idSuffix.strip().replace("-", "").toLowerCase(Locale.ROOT);
    if (hex.length() < MIN_ID_SUFFIX || hex.length() > 32 || !hex.matches("[0-9a-f]+")) {
      throw new IllegalArgumentException(
          "an id suffix must have between " + MIN_ID_SUFFIX + " and 32 hex digits: " + idSuffix);
    }
    return hex;
  }

  private static void requireLimit(int limit) {
    if (limit < 1 || limit > JobQuery.MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + JobQuery.MAX_LIMIT + ": " + limit);
    }
  }

  @Override
  public CancelJobBuilder cancelJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    return new DefaultCancelJobBuilder(jobId);
  }

  @Override
  public RetryJobBuilder retryJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    return new DefaultRetryJobBuilder(jobId);
  }

  @Override
  public RemoveRecurringJobBuilder removeRecurringJob(String name) {
    ValidationUtil.requireName(name, "job name");
    return new DefaultRemoveRecurringJobBuilder(name);
  }

  private final class DefaultCancelJobBuilder implements CancelJobBuilder {
    private final UUID jobId;
    private @Nullable String message;
    private @Nullable Actor actor;

    DefaultCancelJobBuilder(UUID jobId) {
      this.jobId = jobId;
    }

    @Override
    public CancelJobBuilder message(@Nullable String message) {
      this.message = message;
      return this;
    }

    @Override
    public CancelJobBuilder actor(Actor actor) {
      this.actor = Objects.requireNonNull(actor, "actor");
      return this;
    }

    @Override
    public boolean cancel() {
      return actions.cancel(jobId, message, actor);
    }
  }

  private final class DefaultRetryJobBuilder implements RetryJobBuilder {
    private final UUID jobId;
    private @Nullable Actor actor;

    DefaultRetryJobBuilder(UUID jobId) {
      this.jobId = jobId;
    }

    @Override
    public RetryJobBuilder actor(Actor actor) {
      this.actor = Objects.requireNonNull(actor, "actor");
      return this;
    }

    @Override
    public boolean retry() {
      return actions.retry(jobId, actor);
    }
  }

  private final class DefaultRemoveRecurringJobBuilder implements RemoveRecurringJobBuilder {
    private final String name;
    private @Nullable Actor actor;

    DefaultRemoveRecurringJobBuilder(String name) {
      this.name = name;
    }

    @Override
    public RemoveRecurringJobBuilder actor(Actor actor) {
      this.actor = Objects.requireNonNull(actor, "actor");
      return this;
    }

    @Override
    public boolean remove() {
      return actions.removeRecurringJob(name, actor);
    }
  }
}
