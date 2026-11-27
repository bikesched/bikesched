// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * A job name together with the class of its payload, shared by the code that schedules jobs of the
 * name and the code that handles them:
 *
 * <pre>{@code
 * static final TypedJob<Order> ORDER = TypedJob.of("order", Order.class);
 *
 * bikesched.registerJobHandler(ORDER, (job, order) -> shipping.ship(order));
 * bikesched.newJob(ORDER).payload(order).schedule();   // payload(...) only accepts an Order
 * }</pre>
 *
 * <p>A job that returns a result is declared with its result class too, see {@link ResultJob}:
 * {@code TypedJob.of("render", Render.class, Image.class)}. Waits for such jobs and {@linkplain
 * Job#newJobBatch(String, ResultJob) batches} of them are typed by it.
 *
 * <p>Only the name is stored with the job, so a {@code TypedJob} is a compile-time contract: jobs
 * of the name can also be scheduled and handled with the string-based API. A node rejects using one
 * name with two different payload classes.
 *
 * @param <T> the payload type
 */
public sealed interface TypedJob<T extends @Nullable Object> permits PayloadJob, ResultJob {

  /**
   * Returns the job name stored with each job.
   *
   * @return the job name, not blank, at most 200 characters
   */
  String name();

  /**
   * Returns the class payloads are mapped to and from by the {@code JsonMapper}.
   *
   * @return the payload class
   */
  Class<T> payloadType();

  /**
   * Creates a typed job without a result type.
   *
   * @param name the job name stored with each job, not blank, at most 200 characters
   * @param payloadType the class payloads are mapped to and from
   * @param <T> the payload type
   * @return the typed job
   */
  static <T extends @Nullable Object> TypedJob<T> of(String name, Class<T> payloadType) {
    return new PayloadJob<>(name, payloadType);
  }

  /**
   * Creates a typed job with a result type, see {@link ResultJob}.
   *
   * @param name the job name stored with each job, not blank, at most 200 characters
   * @param payloadType the class payloads are mapped to and from
   * @param resultType the class results are mapped to and from
   * @param <T> the payload type
   * @param <R> the result type
   * @return the typed job
   */
  static <T extends @Nullable Object, R extends @Nullable Object> ResultJob<T, R> of(
      String name, Class<T> payloadType, Class<R> resultType) {
    return new ResultJob<>(name, payloadType, resultType);
  }
}
