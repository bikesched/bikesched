// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Objects;
import org.bikesched.internal.util.ValidationUtil;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TypedJob} without a result type, as {@link TypedJob#of(String, Class)} creates it.
 *
 * @param name the job name stored with each job, not blank, at most 200 characters
 * @param payloadType the class payloads are mapped to and from by the {@code JsonMapper}
 * @param <T> the payload type
 */
public record PayloadJob<T extends @Nullable Object>(String name, Class<T> payloadType)
    implements TypedJob<T> {

  /** Validates the name. */
  public PayloadJob {
    ValidationUtil.requireName(name, "job name");
    Objects.requireNonNull(payloadType, "payloadType");
  }
}
