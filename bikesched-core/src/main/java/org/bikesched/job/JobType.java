// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Objects;
import org.bikesched.internal.util.ValidationUtil;

/**
 * A job type together with the class of its payload, shared by the code that schedules jobs of the
 * type and the code that handles them:
 *
 * <pre>{@code
 * static final JobType<Order> ORDER = JobType.of("order", Order.class);
 *
 * bikesched.registerJobHandler(ORDER, (job, order) -> shipping.ship(order));
 * bikesched.newJob(ORDER).payload(order).schedule();   // payload(...) only accepts an Order
 * }</pre>
 *
 * <p>Only the name is stored with the job, so a {@code JobType} is a compile-time contract: jobs of
 * the name can also be scheduled and handled with the string-based API. A node rejects using one
 * name with two different payload classes.
 *
 * @param name the job type stored with each job, not blank, at most 200 characters
 * @param payloadType the class payloads are mapped to and from by the {@code JsonMapper}
 * @param <T> the payload type
 */
public record JobType<T>(String name, Class<T> payloadType) {

  /** Validates the type. */
  public JobType {
    ValidationUtil.requireName(name, "job type");
    Objects.requireNonNull(payloadType, "payloadType");
  }

  /**
   * Creates a job type.
   *
   * @param name the job type stored with each job, not blank, at most 200 characters
   * @param payloadType the class payloads are mapped to and from
   * @param <T> the payload type
   * @return the job type
   */
  public static <T> JobType<T> of(String name, Class<T> payloadType) {
    return new JobType<>(name, payloadType);
  }
}
