// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bikesched.job.FailedJob;

/**
 * Declares a method as the failure handler of a job type (see {@link
 * org.bikesched.job.JobFailureHandler}), registered with {@link
 * JobHandlers#register(org.bikesched.Bikesched, Object)} next to the {@link JobHandler @JobHandler}
 * methods:
 *
 * <pre>{@code
 * public class PaymentJobs {
 *     @JobHandler(type = "charge")
 *     public void charge(Charge charge) { … }
 *
 *     @JobFailureHandler(type = "charge")
 *     public void chargeFailed(FailedJob failed, Charge charge) { deadLetters.insert(failed, charge); }
 * }
 * }</pre>
 *
 * <p>The method must be public, not static and return {@code void}. It has at most two parameters,
 * in any order: the {@link FailedJob} and the payload, mapped by the node's {@code JsonMapper} to
 * the parameter's class (a non-generic class, e.g. a record). Both are optional. Exceptions thrown
 * by the method make the failure handler run again, like those of any failure handler.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JobFailureHandler {

  /**
   * The job type whose failed jobs the method handles, at most 200 characters.
   *
   * @return the job type
   */
  String type();
}
