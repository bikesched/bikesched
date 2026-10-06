// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bikesched.job.Job;

/**
 * Declares a method as the handler of a job type, registered with {@link
 * JobHandlers#register(org.bikesched.Bikesched, Object)}:
 *
 * <pre>{@code
 * public class MailJobs {
 *     @JobHandler(type = "send-mail")
 *     public void sendMail(Job job, Mail mail) { … }
 * }
 * }</pre>
 *
 * <p>The method must be public, not static and return {@code void}. It has at most two parameters,
 * in any order: the {@link Job} and the payload, mapped by the node's {@code JsonMapper} to the
 * parameter's class (a non-generic class, e.g. a record). Both are optional: {@code (Job)}, {@code
 * (Mail)}, {@code (Job, Mail)} and {@code ()} are valid. Exceptions thrown by the method fail the
 * job's attempt like those of any job handler.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JobHandler {

  /**
   * The job type the method handles, at most 200 characters.
   *
   * @return the job type
   */
  String type();
}
