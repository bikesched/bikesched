// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Objects;
import org.bikesched.internal.util.ValidationUtil;
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
 * <p>Only the name is stored with the job, so a {@code TypedJob} is a compile-time contract: jobs
 * of the name can also be scheduled and handled with the string-based API. A node rejects using one
 * name with two different payload classes.
 *
 * @param name the job name stored with each job, not blank, at most 200 characters
 * @param payloadType the class payloads are mapped to and from by the {@code JsonMapper}
 * @param <T> the payload type
 */
public record TypedJob<T extends @Nullable Object>(String name, Class<T> payloadType) {

  /** Validates the name. */
  public TypedJob {
    ValidationUtil.requireName(name, "job name");
    Objects.requireNonNull(payloadType, "payloadType");
  }

  /**
   * Creates a typed job.
   *
   * @param name the job name stored with each job, not blank, at most 200 characters
   * @param payloadType the class payloads are mapped to and from
   * @param <T> the payload type
   * @return the typed job
   */
  public static <T extends @Nullable Object> TypedJob<T> of(String name, Class<T> payloadType) {
    return new TypedJob<>(name, payloadType);
  }
}
