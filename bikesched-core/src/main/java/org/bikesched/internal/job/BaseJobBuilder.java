// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobSettings;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TypedJobFailureHandler;
import org.bikesched.job.TypedJobHandler;
import org.jspecify.annotations.Nullable;

/**
 * The shared settings of the job builder implementations ({@link DefaultJobBuilder}, {@link
 * DefaultIdempotentJobBuilder}, {@link DefaultTransactionalJobBuilder}, {@link
 * DefaultStepJobBuilder}).
 *
 * @param <B> the builder interface
 */
public abstract class BaseJobBuilder<B extends JobSettings<B, P>, P extends @Nullable Object>
    implements JobSettings<B, P> {

  static final int DEFAULT_PRIORITY = 0;
  static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** Stores the jobs built by the job builders; implemented by the owning bikesched node. */
  public interface Scheduler {
    /**
     * {@code connection} is the caller's connection, or {@code null} to use bikesched's own;
     * returns the new job's id, or the existing job's with the job's idempotency key.
     */
    UUID schedule(
        NewJob job,
        @Nullable JobHandler handler,
        @Nullable JobFailureHandler failureHandler,
        @Nullable Connection connection);

    /** Registers a recurring job; {@code firstRun} is {@code null} for the schedule's next run. */
    UUID scheduleRecurring(
        RecurringJob job,
        @Nullable JobHandler handler,
        @Nullable JobFailureHandler failureHandler,
        @Nullable Instant firstRun);

    /** Parses a recurrence expression with the node's recurrence parsers. */
    Recurrence parseRecurrence(String expression);

    /** Registers the handler if given and new; throws if the name has no handler then. */
    void requireHandler(String name, @Nullable JobHandler handler);
  }

  final String name;
  final Class<P> payloadType;
  final JsonMapper jsonMapper;
  final Scheduler scheduler;
  @Nullable String payload;

  /** Set by the builders implementing {@code JobHandlerSettings}, otherwise {@code null}. */
  @Nullable JobHandler handler;

  /** Set by the builders implementing {@code JobHandlerSettings}, otherwise {@code null}. */
  @Nullable JobFailureHandler failureHandler;

  int priority = DEFAULT_PRIORITY;
  int maxAttempts = DEFAULT_MAX_ATTEMPTS;
  @Nullable Duration timeout;

  /** Set by the builders of one-off jobs scheduled through bikesched, otherwise {@code null}. */
  @Nullable String idempotencyKey;

  BaseJobBuilder(String name, Class<P> payloadType, JsonMapper jsonMapper, Scheduler scheduler) {
    this.name = name;
    this.payloadType = payloadType;
    this.jsonMapper = jsonMapper;
    this.scheduler = scheduler;
  }

  /** Continues building with the settings of the given builder. */
  BaseJobBuilder(BaseJobBuilder<?, P> settings) {
    this(settings.name, settings.payloadType, settings.jsonMapper, settings.scheduler);
    this.payload = settings.payload;
    this.handler = settings.handler;
    this.failureHandler = settings.failureHandler;
    this.priority = settings.priority;
    this.maxAttempts = settings.maxAttempts;
    this.timeout = settings.timeout;
    this.idempotencyKey = settings.idempotencyKey;
  }

  /** This builder as its interface type. */
  abstract B self();

  @Override
  public B payload(@Nullable P payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return self();
  }

  @Override
  public B payloadJson(@Nullable String json) {
    this.payload = json;
    return self();
  }

  /** Sets the handler, for the builders implementing {@code JobHandlerSettings}. */
  B setHandler(JobHandler handler) {
    this.handler = Objects.requireNonNull(handler, "handler");
    return self();
  }

  /** Sets a handler receiving the mapped payload, see {@link #setHandler(JobHandler)}. */
  B setHandler(TypedJobHandler<? super P> handler) {
    Objects.requireNonNull(handler, "handler");
    return setHandler(job -> handler.handle(job, job.payload(payloadType)));
  }

  /** Sets the failure handler, for the builders implementing {@code JobHandlerSettings}. */
  B setFailureHandler(JobFailureHandler handler) {
    this.failureHandler = Objects.requireNonNull(handler, "handler");
    return self();
  }

  /** Sets a failure handler receiving the mapped payload. */
  B setFailureHandler(TypedJobFailureHandler<? super P> handler) {
    return setFailureHandler(JobRegistry.typed(handler, payloadType));
  }

  /** Sets the idempotency key, for the builders of one-off jobs scheduled through bikesched. */
  B setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = ValidationUtil.requireKey(idempotencyKey, "idempotency key");
    return self();
  }

  @Override
  public B priority(int priority) {
    this.priority = priority;
    return self();
  }

  @Override
  public B maxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
    }
    this.maxAttempts = maxAttempts;
    return self();
  }

  @Override
  public B timeout(Duration timeout) {
    ValidationUtil.requirePositive(timeout, "timeout");
    this.timeout = timeout;
    return self();
  }

  /** A one-off job with the current settings, running at or after the given time. */
  NewJob newJob(Instant at) {
    return new NewJob(
        name, payload, at, priority, maxAttempts, timeout, null, null, idempotencyKey);
  }

  static Instant after(Duration delay) {
    Objects.requireNonNull(delay, "delay");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative: " + delay);
    }
    return Instant.now().plus(delay);
  }
}
