package bikesched.core;

/**
 * Keys of the logging context ({@link org.slf4j.MDC}) that bikesched fills on its own threads, for
 * use in log patterns (e.g. {@code %X{bikesched.jobId}} with Logback) or via {@link
 * org.slf4j.MDC#get(String)} inside job handlers.
 *
 * <table>
 *   <caption>Keys set per thread</caption>
 *   <tr><th>Thread</th><th>{@link #NODE_ID}</th><th>{@link #JOB_ID}</th><th>{@link #JOB_TYPE}</th>
 *       <th>{@link #STEP_NAME}</th></tr>
 *   <tr><td>heartbeat ({@code bikesched-heartbeat})</td><td>yes</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>poller ({@code bikesched-poller})</td><td>yes</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>job execution incl. the handler ({@code bikesched-job-N})</td><td>yes</td><td>yes</td><td>yes</td>
 *       <td>while a step runs</td></tr>
 * </table>
 *
 * <p>The keys are removed again once the task (or step) finishes. Application threads calling
 * bikesched, e.g. to schedule jobs, are left untouched.
 */
public final class BikeschedLogContext {

  /** Id of the bikesched node. */
  public static final String NODE_ID = "bikesched.nodeId";

  /** Id of the job being executed. */
  public static final String JOB_ID = "bikesched.jobId";

  /** Type of the job being executed. */
  public static final String JOB_TYPE = "bikesched.jobType";

  /** Name of the {@link Job#step(String, Class, StepHandler) step} being executed. */
  public static final String STEP_NAME = "bikesched.stepName";

  private BikeschedLogContext() {}
}
