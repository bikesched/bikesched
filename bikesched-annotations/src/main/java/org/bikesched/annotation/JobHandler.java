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

  /**
   * Optional recurring schedule: a recurrence expression, parsed when the handler is registered by
   * the node's {@link org.bikesched.job.RecurrenceParser}s (like {@link
   * org.bikesched.job.JobBuilder#schedule(String)}). By default a node parses ISO-8601 durations
   * (e.g. {@code PT5M}, at a fixed rate); other formats are configured on the node.
   *
   * @return the schedule expression, empty for a one-off handler
   */
  String schedule() default "";

  /**
   * Optional fixed delay: an ISO-8601 duration (e.g. {@code PT15M}), positive. The handler's job
   * type becomes a recurring job whose next run is this long after the previous occurrence ended
   * ({@link org.bikesched.job.Recurrence#everyAfterEnd(java.time.Duration)}), like Spring's {@code
   * fixedDelay}. Not together with {@link #schedule()}.
   *
   * @return the delay, empty for none
   */
  String fixedDelay() default "";

  /**
   * Optional delay of a new first occurrence of a recurring handler ({@link #schedule()} or {@link
   * #fixedDelay()}): an ISO-8601 duration, not negative, e.g. {@code PT0S} to run right away (like
   * {@link org.bikesched.job.JobBuilder#schedule(String, java.time.Duration)}). By default the
   * first occurrence is the recurrence's next run from now. Registration is idempotent: restarting
   * a node keeps the pending occurrence, the delay only applies to a new first occurrence.
   *
   * @return the delay, empty for the default
   */
  String initialDelay() default "";
}
