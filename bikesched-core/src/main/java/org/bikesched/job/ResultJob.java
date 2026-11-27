// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Objects;
import org.bikesched.internal.util.ValidationUtil;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TypedJob} that also declares the class of its result, as {@link TypedJob#of(String,
 * Class, Class)} creates it:
 *
 * <pre>{@code
 * static final ResultJob<Render, Image> RENDER = TypedJob.of("render", Render.class, Image.class);
 *
 * bikesched.registerResultJobHandler(RENDER, (job, render) -> images.render(render));
 * Image image = job.waitForJob(renderId, RENDER).orElseThrow();   // no result class to pass
 * }</pre>
 *
 * <p>Like the payload class, the result class is a compile-time contract: the result is stored as
 * JSON and mapped by the {@code JsonMapper} of the node reading it.
 *
 * @param name the job name stored with each job, not blank, at most 200 characters
 * @param payloadType the class payloads are mapped to and from by the {@code JsonMapper}
 * @param resultType the class results are mapped to and from by the {@code JsonMapper}
 * @param <T> the payload type
 * @param <R> the result type
 */
public record ResultJob<T extends @Nullable Object, R extends @Nullable Object>(
    String name, Class<T> payloadType, Class<R> resultType) implements TypedJob<T> {

  /** Validates the name. */
  public ResultJob {
    ValidationUtil.requireName(name, "job name");
    Objects.requireNonNull(payloadType, "payloadType");
    Objects.requireNonNull(resultType, "resultType");
  }
}
